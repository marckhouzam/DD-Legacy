/*******************************************************************************
 * Copyright (c) 2006, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *     Ericsson			  - Modified for handling of multiple execution contexts	
 *******************************************************************************/
package org.eclipse.dd.mi.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;

import org.eclipse.cdt.core.IAddress;
import org.eclipse.cdt.utils.Addr32;
import org.eclipse.cdt.utils.Addr64;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.dd.dsf.concurrent.CountingRequestMonitor;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.datamodel.AbstractDMContext;
import org.eclipse.dd.dsf.datamodel.DMContexts;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.dsf.debug.service.ICachingService;
import org.eclipse.dd.dsf.debug.service.IRunControl;
import org.eclipse.dd.dsf.debug.service.IStack;
import org.eclipse.dd.dsf.debug.service.IStack2;
import org.eclipse.dd.dsf.debug.service.IRunControl.IExecutionDMContext;
import org.eclipse.dd.dsf.debug.service.IRunControl.IResumedDMEvent;
import org.eclipse.dd.dsf.debug.service.IRunControl.ISuspendedDMEvent;
import org.eclipse.dd.dsf.debug.service.IRunControl.StateChangeReason;
import org.eclipse.dd.dsf.debug.service.command.CommandCache;
import org.eclipse.dd.dsf.debug.service.command.ICommandControlService;
import org.eclipse.dd.dsf.service.AbstractDsfService;
import org.eclipse.dd.dsf.service.DsfServiceEventHandler;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.mi.internal.MIPlugin;
import org.eclipse.dd.mi.service.command.commands.MIStackInfoDepth;
import org.eclipse.dd.mi.service.command.commands.MIStackListArguments;
import org.eclipse.dd.mi.service.command.commands.MIStackListFrames;
import org.eclipse.dd.mi.service.command.commands.MIStackListLocals;
import org.eclipse.dd.mi.service.command.events.IMIDMEvent;
import org.eclipse.dd.mi.service.command.events.MIStoppedEvent;
import org.eclipse.dd.mi.service.command.output.MIArg;
import org.eclipse.dd.mi.service.command.output.MIFrame;
import org.eclipse.dd.mi.service.command.output.MIStackInfoDepthInfo;
import org.eclipse.dd.mi.service.command.output.MIStackListArgumentsInfo;
import org.eclipse.dd.mi.service.command.output.MIStackListFramesInfo;
import org.eclipse.dd.mi.service.command.output.MIStackListLocalsInfo;
import org.osgi.framework.BundleContext;

public class MIStack extends AbstractDsfService
	implements IStack2, ICachingService
{
    protected static class MIFrameDMC extends AbstractDMContext 
        implements IFrameDMContext
    {
        private final int fLevel;
        // public MIFrameDMC(MIStack service, int level) {
        public MIFrameDMC(String sessionId, IExecutionDMContext execDmc, int level) {
            super(sessionId, new IDMContext[] { execDmc });
            fLevel = level;
        }
        
        public int getLevel() { return fLevel; }
        
        @Override
        public boolean equals(Object other) {
            return super.baseEquals(other) && ((MIFrameDMC)other).fLevel == fLevel;
        }
        
        @Override
        public int hashCode() {
            return super.baseHashCode() ^ fLevel;
        }
        
        @Override
        public String toString() { 
            return baseToString() + ".frame[" + fLevel + "]";  //$NON-NLS-1$ //$NON-NLS-2$
        }            
    }
       
    protected static class MIVariableDMC extends AbstractDMContext
        implements IVariableDMContext
    {
        public enum Type { ARGUMENT, LOCAL }
        final private Type fType;
        final private int fIndex;

        public MIVariableDMC(MIStack service, IFrameDMContext frame, Type type, int index) {
            super(service, new IDMContext[] { frame });
            fIndex = index;
            fType = type;
        }
        
        public int getIndex() { return fIndex; }
        public Type getType() { return fType; }
        
        @Override
        public boolean equals(Object other) {
            return super.baseEquals(other) && 
                   ((MIVariableDMC)other).fType == fType && 
                   ((MIVariableDMC)other).fIndex == fIndex;
        }
        
        @Override
        public int hashCode() {
            int typeFactor = 0;
            if (fType == Type.LOCAL) typeFactor = 2;
            else if (fType == Type.ARGUMENT) typeFactor = 3;
            return super.baseHashCode() ^ typeFactor ^ fIndex;
        }
        
        @Override
        public String toString() { 
            return baseToString() + ".variable(" + fType + ")[" + fIndex + "]";  //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        }            
    }
    
	private CommandCache fMICommandCache;
    private MIStoppedEvent fCachedStoppedEvent;
    private IRunControl fRunControl;

	public MIStack(DsfSession session) 
	{
		super(session);
	}

    @Override
    protected BundleContext getBundleContext() 
    {
        return MIPlugin.getBundleContext();
    }
    
    @Override
    public void initialize(final RequestMonitor rm) {
        super.initialize(
            new RequestMonitor(getExecutor(), rm) { 
                @Override
                protected void handleSuccess() {
                    doInitialize(rm);
                }
            });
    }

    private void doInitialize(RequestMonitor rm) {
    	ICommandControlService commandControl = getServicesTracker().getService(ICommandControlService.class);
        fMICommandCache = new CommandCache(getSession(), commandControl);
        fMICommandCache.setContextAvailable(commandControl.getContext(), true);
        fRunControl = getServicesTracker().getService(IRunControl.class);

        getSession().addServiceEventListener(this, null);
        register(new String[]{IStack.class.getName(), MIStack.class.getName()}, new Hashtable<String,String>());
        rm.done();
    }

    @Override
    public void shutdown(RequestMonitor rm) 
    {
        unregister();
        getSession().removeServiceEventListener(this);
        fMICommandCache.reset();
        super.shutdown(rm);
    }

    @SuppressWarnings("unchecked")
    public void getModelData(IDMContext dmc, DataRequestMonitor<?> rm) {
        if (dmc instanceof MIFrameDMC) {
            getFrameData((MIFrameDMC)dmc, (DataRequestMonitor<IFrameDMData>)rm);
            // getFrameData invokes rm 
        } else if (dmc instanceof MIVariableDMC) {
            getVariableData((MIVariableDMC)dmc, (DataRequestMonitor<IVariableDMData>)rm);
            // getVariablesData invokes rm 
        } else {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Unknown DMC type", null)); //$NON-NLS-1$
            rm.done();
        }
    }

    /**
     * Creates a frame context.  This method is intended to be used by other MI 
     * services and sub-classes which need to create a frame context directly.
     * <p>
     * Sub-classes can override this method to provide custom stack frame 
     * context implementation. 
     * </p>
     * @param execDmc Execution context that this frame is to be a child of.
     * @param level Level of the new context.
     * @return A new frame context.
     */
    public IFrameDMContext createFrameDMContext(IExecutionDMContext execDmc, int level) {
        return new MIFrameDMC(getSession().getId(), execDmc, level);
    }
    
    public void getFrames(final IDMContext ctx, final DataRequestMonitor<IFrameDMContext[]> rm) {
    	getFrames(ctx, 0, ALL_FRAMES, rm);
    }

	public void getFrames(final IDMContext ctx, final int startIndex, final int endIndex, final DataRequestMonitor<IFrameDMContext[]> rm) {

	    if (startIndex < 0 || endIndex > 0 && endIndex < startIndex) {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Invalid stack frame range [" + startIndex + ',' + endIndex + ']', null)); //$NON-NLS-1$
            rm.done();
            return;
	    }

		final IMIExecutionDMContext execDmc = DMContexts.getAncestorOfType(ctx, IMIExecutionDMContext.class);
	    
	    if (execDmc == null) {
            //rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, -1, "No frame context found in " + ctx, null)); //$NON-NLS-1$
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Invalid context " + ctx, null)); //$NON-NLS-1$
            rm.done();
            return;
        }

	    // Make sure the thread is stopped
	    if (!fRunControl.isSuspended(execDmc)) {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_STATE, "Context is running: " + ctx, null)); //$NON-NLS-1$
	    	rm.done();
	    	return;
	    }

	    if (startIndex == 0 && endIndex == 0) {
	        // Try to retrieve the top stack frame from the cached stopped event.
	        if (fCachedStoppedEvent != null && 
	            fCachedStoppedEvent.getFrame() != null && 
	            execDmc.equals(fCachedStoppedEvent.getDMContext())) 
	        {
	            rm.setData(new IFrameDMContext[] { createFrameDMContext(execDmc, fCachedStoppedEvent.getFrame().getLevel()) });
	            rm.done();
	            return;
	        }
	    }

	    final MIStackListFrames miStackListCmd;
	    // firstIndex is the first index retrieved
	    final int firstIndex;
	    if (endIndex >= 0) {
	    	miStackListCmd = new MIStackListFrames(execDmc, startIndex, endIndex);
	    	firstIndex = startIndex;
	    } else {
	    	miStackListCmd = new MIStackListFrames(execDmc);
	    	firstIndex = 0;
	    }
		fMICommandCache.execute(
            miStackListCmd,
            new DataRequestMonitor<MIStackListFramesInfo>(getExecutor(), rm) { 
                @Override
                protected void handleSuccess() {
                    rm.setData(getFrames(execDmc, getData(), firstIndex, endIndex, startIndex));
                    rm.done();
                }
            });
	}
    
    public void getTopFrame(final IDMContext ctx, final DataRequestMonitor<IFrameDMContext> rm) {     
        final IMIExecutionDMContext execDmc = DMContexts.getAncestorOfType(ctx, IMIExecutionDMContext.class);
        if (execDmc == null) {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Invalid context" + ctx, null)); //$NON-NLS-1$
            rm.done();
            return;
        }
        
        // Try to retrieve the top stack frame from the cached stopped event.
        if (fCachedStoppedEvent != null && 
            fCachedStoppedEvent.getFrame() != null && 
            execDmc.equals(fCachedStoppedEvent.getDMContext())) 
        {
            rm.setData(createFrameDMContext(execDmc, fCachedStoppedEvent.getFrame().getLevel()));
            rm.done();
            return;
        }
        
        // If stopped event is not available or doesn't contain frame info, 
        // query top stack frame
        getFrames(
            ctx, 
            0,
            0,
            new DataRequestMonitor<IFrameDMContext[]>(getExecutor(), rm) { 
                @Override
                protected void handleSuccess() {
                    rm.setData(getData()[0]);
                    rm.done();
                }
            });
    }
    
    //private MIFrameDMC[] getFrames(DsfMIStackListFramesInfo info) {
    private IFrameDMContext[] getFrames(IMIExecutionDMContext execDmc, MIStackListFramesInfo info, int firstIndex, int lastIndex, int startIndex) {
        int length = info.getMIFrames().length;
        if (lastIndex > 0) {
        	int limit= lastIndex - startIndex + 1;
        	if (limit < length) {
        		length = limit;
        	}
        }
		IFrameDMContext[] frameDMCs = new MIFrameDMC[length];
        for (int i = 0; i < length; i++) {
	        //frameDMCs[i] = new MIFrameDMC(this, info.getMIFrames()[i].getLevel()); 
        	final MIFrame frame= info.getMIFrames()[i + startIndex - firstIndex];
			assert startIndex + i == frame.getLevel();
            frameDMCs[i] = createFrameDMContext(execDmc, frame.getLevel()); 
        }
        return frameDMCs;
    }
    

    
    public void getFrameData(final IFrameDMContext frameDmc, final DataRequestMonitor<IFrameDMData> rm) {
        if (!(frameDmc instanceof MIFrameDMC)) {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Invalid context type " + frameDmc, null)); //$NON-NLS-1$
            rm.done();
            return;
        }

        final MIFrameDMC miFrameDmc = (MIFrameDMC)frameDmc;
        
        IMIExecutionDMContext execDmc = DMContexts.getAncestorOfType(frameDmc, IMIExecutionDMContext.class);
        if (execDmc == null) { 
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "No execution context found in " + frameDmc, null)); //$NON-NLS-1$
            rm.done();
            return;
        }

        /**
         * Base class for the IFrameDMData object that uses an MIFrame object to 
         * provide the data.  Sub-classes must provide the MIFrame object
         */
        abstract class FrameData implements IFrameDMData
        {
            abstract protected MIFrame getMIFrame();

            public IAddress getAddress() {
                String addr = getMIFrame().getAddress();
                if (addr.startsWith("0x")) { //$NON-NLS-1$
                    addr = addr.substring(2);
                }
                if (addr.length() <= 8) {
                    return new Addr32(getMIFrame().getAddress());
                } else {
                    return new Addr64(getMIFrame().getAddress());
                }                    
            }

            public int getColumn() { return 0; }

            public String getFile() { return getMIFrame().getFile(); }
            public int getLine() { return getMIFrame().getLine(); }
            public String getFunction() { return getMIFrame().getFunction(); }
            
            @Override
            public String toString() { return getMIFrame().toString(); }
        }

        // If requested frame is the top stack frame, try to retrieve it from 
        // the stopped event data.
        class FrameDataFromStoppedEvent extends FrameData {
            private final MIStoppedEvent fEvent;
            FrameDataFromStoppedEvent(MIStoppedEvent event) { fEvent = event; }
            @Override
            protected MIFrame getMIFrame() { return fEvent.getFrame(); }
        }
        
        // Retrieve the top stack frame from the stopped event only if the selected thread is the one on which stopped event 
        // is raised
        if (fCachedStoppedEvent != null && 
            execDmc.equals(fCachedStoppedEvent.getDMContext()) && 
            miFrameDmc.fLevel == 0 && 
            fCachedStoppedEvent.getFrame() != null) 
        {
            rm.setData(new FrameDataFromStoppedEvent(fCachedStoppedEvent));
            rm.done();
            return;
        } 

        // If not, retrieve the full list of frame data.
        class FrameDataFromMIStackFrameListInfo extends FrameData {
            private MIStackListFramesInfo fFrameDataCacheInfo;
            private int fFrameIndex;

            FrameDataFromMIStackFrameListInfo(MIStackListFramesInfo info, int index) {
                fFrameDataCacheInfo = info;
                fFrameIndex = index;
            }

            @Override
            protected MIFrame getMIFrame() { return fFrameDataCacheInfo.getMIFrames()[fFrameIndex]; }
        }

        fMICommandCache.execute(
            new MIStackListFrames(execDmc),
            new DataRequestMonitor<MIStackListFramesInfo>(getExecutor(), rm) { 
                @Override
                protected void handleSuccess() {
                    // Find the index to the correct MI frame object.
                    int idx = findFrameIndex(getData().getMIFrames(), miFrameDmc.fLevel);
                    if (idx == -1) {
                        rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Invalid frame " + frameDmc, null));  //$NON-NLS-1$
                        rm.done();
                        return;
                    }
                    
                    // Create the data object.
                    rm.setData(new FrameDataFromMIStackFrameListInfo(getData(), idx));
                    rm.done();
                }
            }); 
    }

	public void getArguments(final IFrameDMContext frameDmc, final DataRequestMonitor<IVariableDMContext[]> rm) {
        final IMIExecutionDMContext execDmc = DMContexts.getAncestorOfType(frameDmc, IMIExecutionDMContext.class);
	    if (execDmc == null) { 
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "No execution context found in " + frameDmc, null)); //$NON-NLS-1$
            rm.done();
            return;
        }

        
        // If requested frame is the top stack frame, try to retrieve it from 
        // the stopped event data.
        if (frameDmc.getLevel() == 0 && 
            fCachedStoppedEvent != null && 
            fCachedStoppedEvent.getFrame() != null &&
            execDmc.equals(fCachedStoppedEvent.getDMContext()) && 
            fCachedStoppedEvent.getFrame().getArgs() != null) 
        {
            rm.setData(makeVariableDMCs(
                frameDmc, MIVariableDMC.Type.ARGUMENT, fCachedStoppedEvent.getFrame().getArgs().length)); 
            rm.done();
            return;
        }

        // If not, retrieve the full list of frame data.
        fMICommandCache.execute(
            new MIStackListArguments(execDmc, true),
            new DataRequestMonitor<MIStackListArgumentsInfo>(getExecutor(), rm) { 
                @Override
                protected void handleSuccess() {
                    // Find the index to the correct MI frame object.
                    // Note: this is a short-cut, but it won't work once we implement retrieving
                    // partial lists of stack frames.
                    int idx = frameDmc.getLevel();
                    if (idx == -1 || idx >= getData().getMIFrames().length) {
                        rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_STATE, "Invalid frame " + frameDmc, null));  //$NON-NLS-1$
                        rm.done();
                        return;
                    }
                    
                    // Create the variable array out of MIArg array.
                    MIArg[] args = getData().getMIFrames()[idx].getArgs();
                    if (args == null) args = new MIArg[0]; 
                    rm.setData(makeVariableDMCs(frameDmc, MIVariableDMC.Type.ARGUMENT, args.length));
                    rm.done();
                }
            }); 
    }    
    
    public void getVariableData(IVariableDMContext variableDmc, final DataRequestMonitor<IVariableDMData> rm) {
        if (!(variableDmc instanceof MIVariableDMC)) {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Invalid context type " + variableDmc, null)); //$NON-NLS-1$
            rm.done();
            return;            
        }
        final MIVariableDMC miVariableDmc = (MIVariableDMC)variableDmc;
        
        // Extract the frame DMC from the variable DMC.
        final MIFrameDMC frameDmc = DMContexts.getAncestorOfType(variableDmc, MIFrameDMC.class);
        if (frameDmc == null) { 
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "No frame context found in " + variableDmc, null)); //$NON-NLS-1$
            rm.done();
            return;
        }

        final IMIExecutionDMContext execDmc = DMContexts.getAncestorOfType(frameDmc, IMIExecutionDMContext.class);
        if (execDmc == null) { 
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "No execution context found in " + frameDmc, null)); //$NON-NLS-1$
            rm.done();
            return;
        }

        /**
         * Same as with frame objects, this is a base class for the IVariableDMData object that uses an MIArg object to 
         * provide the data.  Sub-classes must supply the MIArg object.
         */
    	class VariableData implements IVariableDMData {
    		private MIArg dsfMIArg;
    		VariableData(MIArg arg){
    			dsfMIArg = arg;
    		}
    		public String getName() { return dsfMIArg.getName(); }
    		public String getValue() { return dsfMIArg.getValue(); }
    		@Override
    		public String toString() { return dsfMIArg.toString(); }	
    	}    	

        // Check if the stopped event can be used to extract the variable value. 
        if (execDmc != null && miVariableDmc.fType == MIVariableDMC.Type.ARGUMENT &&
            frameDmc.fLevel == 0 && fCachedStoppedEvent != null && fCachedStoppedEvent.getFrame() != null &&
            execDmc.equals(fCachedStoppedEvent.getDMContext()) && 
            fCachedStoppedEvent.getFrame().getArgs() != null) 
        {
            if (miVariableDmc.fIndex >= fCachedStoppedEvent.getFrame().getArgs().length) {
                rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, -1, "Invalid variable " + miVariableDmc, null));  //$NON-NLS-1$
                rm.done();
                return;
            }

            rm.setData(new VariableData(fCachedStoppedEvent.getFrame().getArgs()[miVariableDmc.fIndex]));
            rm.done();
            return;
        }

        if (miVariableDmc.fType == MIVariableDMC.Type.ARGUMENT){
	        fMICommandCache.execute(
	            new MIStackListArguments(execDmc, true),
	            new DataRequestMonitor<MIStackListArgumentsInfo>(getExecutor(), rm) { 
	                @Override
	                protected void handleSuccess() {
	                    // Find the correct frame and argument
	                    if ( frameDmc.fLevel >= getData().getMIFrames().length ||
	                        miVariableDmc.fIndex >= getData().getMIFrames()[frameDmc.fLevel].getArgs().length )
	                    {
	                        rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Invalid variable " + miVariableDmc, null));  //$NON-NLS-1$
	                        rm.done();
	                        return;
	                    }
	                    
	                    // Create the data object.
	                    rm.setData(new VariableData(getData().getMIFrames()[frameDmc.fLevel].getArgs()[miVariableDmc.fIndex]));
	                    rm.done();
	                }});
        }//if
        if (miVariableDmc.fType == MIVariableDMC.Type.LOCAL){
            fMICommandCache.execute(
                    new MIStackListLocals(frameDmc, true),
                    new DataRequestMonitor<MIStackListLocalsInfo>(getExecutor(), rm) { 
                        @Override
                        protected void handleSuccess() {
   		                    
		                    // Create the data object.
		                    MIArg[] locals = getData().getLocals();
		                    if (locals.length > miVariableDmc.fIndex) {
		                    	rm.setData(new VariableData(locals[miVariableDmc.fIndex]));
		                    } else {
		                        rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Invalid variable " + miVariableDmc, null));  //$NON-NLS-1$
		                    }
		                    rm.done();
                        }
                    });

        }//if   

    }

    private MIVariableDMC[] makeVariableDMCs(IFrameDMContext frame, MIVariableDMC.Type type, int count) {
        MIVariableDMC[] variables = new MIVariableDMC[count];
        for (int i = 0; i < count; i++) {
            variables[i]= new MIVariableDMC(this, frame, type, i);
        }
        return variables;
    }

    private int findFrameIndex(MIFrame[] frames, int level) {
        for (int idx = 0; idx < frames.length; idx++) {
            if (frames[idx].getLevel() == level) {
                return idx;
            }
        }
        return -1;
    }
    
    
    public void getLocals(final IFrameDMContext frameDmc, final DataRequestMonitor<IVariableDMContext[]> rm) {

        final List<IVariableDMContext> localsList = new ArrayList<IVariableDMContext>();
        
        final CountingRequestMonitor countingRm = new CountingRequestMonitor(getExecutor(), rm) {
            @Override
            protected void handleSuccess() {
                rm.setData( localsList.toArray(new IVariableDMContext[localsList.size()]) );
                rm.done();
            }
        };
        countingRm.setDoneCount(2);
        
        getArguments(
            frameDmc,
            new DataRequestMonitor<IVariableDMContext[]>(getExecutor(), countingRm) { 
                @Override
                protected void handleSuccess() {
                    localsList.addAll( Arrays.asList(getData()) );
                    countingRm.done();
                }
            }); 
        
	    fMICommandCache.execute(
                new MIStackListLocals(frameDmc, true),
                new DataRequestMonitor<MIStackListLocalsInfo>(getExecutor(), countingRm) { 
                    @Override
                    protected void handleSuccess() {
                        localsList.addAll( Arrays.asList(
                            makeVariableDMCs(frameDmc, MIVariableDMC.Type.LOCAL, getData().getLocals().length)) );
                        countingRm.done();
                    }
                }); 
    }

    public void getStackDepth(IDMContext dmc, final int maxDepth, final DataRequestMonitor<Integer> rm) {
        IMIExecutionDMContext execDmc = DMContexts.getAncestorOfType(dmc, IMIExecutionDMContext.class);
	    if (execDmc != null) {
	    	// Make sure the thread is stopped
	    	if (!fRunControl.isSuspended(execDmc)) {
	    		rm.setData(0);
	    		rm.done();
	    		return;
	    	}

	    	MIStackInfoDepth depthCommand = null;
	    	if (maxDepth > 0) depthCommand = new MIStackInfoDepth(execDmc, maxDepth);
	    	else depthCommand = new MIStackInfoDepth(execDmc);

	    	fMICommandCache.execute(
	    			depthCommand,
	    			new DataRequestMonitor<MIStackInfoDepthInfo>(getExecutor(), rm) { 
	    				@Override
	    				protected void handleSuccess() {
	    					rm.setData(getData().getDepth());
	    					rm.done();
	    				}
	    			});
        } else {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Invalid context", null)); //$NON-NLS-1$
            rm.done();
        }
    }

    /**
     * @nooverride This method is not intended to be re-implemented or extended by clients.
     * @noreference This method is not intended to be referenced by clients.
     */
    @DsfServiceEventHandler 
    public void eventDispatched(IResumedDMEvent e) {
    	fMICommandCache.setContextAvailable(e.getDMContext(), false);
        if (e.getReason() != StateChangeReason.STEP) {
            fCachedStoppedEvent = null;
            fMICommandCache.reset();
        }
    }
    
    /**
     * @nooverride This method is not intended to be re-implemented or extended by clients.
     * @noreference This method is not intended to be referenced by clients.
     * @since 1.1
     */
    @DsfServiceEventHandler 
    public void eventDispatched(ISuspendedDMEvent e) {
    	fMICommandCache.setContextAvailable(e.getDMContext(), true);
        fMICommandCache.reset();
    }
    

    /**
     * @nooverride This method is not intended to be re-implemented or extended by clients.
     * @noreference This method is not intended to be referenced by clients.
     * @since 1.1
     */
    @DsfServiceEventHandler 
    public void eventDispatched(IMIDMEvent e) {
    	if (e.getMIEvent() instanceof MIStoppedEvent) {
    		fCachedStoppedEvent = (MIStoppedEvent)e.getMIEvent();
    	}
    }

    /**
     * This method is left for API compatibility only.
     * @nooverride This method is not intended to be re-implemented or extended by clients.
     * @noreference This method is not intended to be referenced by clients.
     */
    @DsfServiceEventHandler 
    public void eventDispatched(MIRunControl.ContainerSuspendedEvent e) {
    }
    
    /**
     * {@inheritDoc}
     * @since 1.1
     */
	public void flushCache(IDMContext context) {
        fMICommandCache.reset(context);
	}

}
