/*******************************************************************************
 * Copyright (c) 2006, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *     Ericsson			  - Modified for additional features in DSF Reference Implementation
 *******************************************************************************/
package org.eclipse.dd.mi.service;

import java.util.Hashtable;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.datamodel.AbstractDMContext;
import org.eclipse.dd.dsf.datamodel.DMContexts;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.dsf.debug.service.ICachingService;
import org.eclipse.dd.dsf.debug.service.IRegisters;
import org.eclipse.dd.dsf.debug.service.IRunControl;
import org.eclipse.dd.dsf.debug.service.IExpressions.IExpressionDMContext;
import org.eclipse.dd.dsf.debug.service.IExpressions.IExpressionDMData;
import org.eclipse.dd.dsf.debug.service.IRunControl.IContainerDMContext;
import org.eclipse.dd.dsf.debug.service.IRunControl.StateChangeReason;
import org.eclipse.dd.dsf.debug.service.command.CommandCache;
import org.eclipse.dd.dsf.debug.service.command.ICommandControlService;
import org.eclipse.dd.dsf.service.AbstractDsfService;
import org.eclipse.dd.dsf.service.DsfServiceEventHandler;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.mi.internal.MIPlugin;
import org.eclipse.dd.mi.service.command.commands.MIDataListRegisterNames;
import org.eclipse.dd.mi.service.command.commands.MIDataListRegisterValues;
import org.eclipse.dd.mi.service.command.output.MIDataListRegisterNamesInfo;
import org.eclipse.dd.mi.service.command.output.MIDataListRegisterValuesInfo;
import org.eclipse.dd.mi.service.command.output.MIInfo;
import org.eclipse.dd.mi.service.command.output.MIRegisterValue;
import org.osgi.framework.BundleContext;

/**
 * 
 * <p> 
 * Implementation note:
 * This class implements event handlers for the events that are generated by 
 * this service itself.  When the event is dispatched, these handlers will
 * be called first, before any of the clients.  These handlers update the 
 * service's internal state information to make them consistent with the 
 * events being issued.  Doing this in the handlers as opposed to when 
 * the events are generated, guarantees that the state of the service will
 * always be consistent with the events.
 */

public class MIRegisters extends AbstractDsfService implements IRegisters, ICachingService {
	private static final String BLANK_STRING = ""; //$NON-NLS-1$
    /*
     * Support class used to construct Register Group DMCs.
     */
	
    public static class MIRegisterGroupDMC extends AbstractDMContext implements IRegisterGroupDMContext {
        private int fGroupNo;
        private String fGroupName;

        public MIRegisterGroupDMC(MIRegisters service, IContainerDMContext contDmc, int groupNo, String groupName) {
            super(service.getSession().getId(), new IDMContext[] { contDmc });
            fGroupNo = groupNo;
            fGroupName = groupName;
        }

        public int getGroupNo() { return fGroupNo; }
        public String getName() { return fGroupName; }

        @Override
        public boolean equals(Object other) {
            return ((super.baseEquals(other)) && (((MIRegisterGroupDMC) other).fGroupNo == fGroupNo) && 
                    (((MIRegisterGroupDMC) other).fGroupName.equals(fGroupName)));
        }
        
        @Override
        public int hashCode() { return super.baseHashCode() ^ fGroupNo; }
        @Override
        public String toString() { return baseToString() + ".group[" + fGroupNo + "]"; }             //$NON-NLS-1$ //$NON-NLS-2$
    }
       
    /*
     * Support class used to construct Register DMCs.
     */
    
    public static class MIRegisterDMC extends AbstractDMContext implements IRegisterDMContext {
        private int fRegNo;
        private String fRegName;

        public MIRegisterDMC(MIRegisters service, MIRegisterGroupDMC group, int regNo, String regName) {
            super(service.getSession().getId(), 
                    new IDMContext[] { group });
              fRegNo = regNo;
              fRegName = regName;
        }

        public MIRegisterDMC(MIRegisters service, MIRegisterGroupDMC group, IMIExecutionDMContext execDmc, int regNo, String regName) {
            super(service.getSession().getId(), 
                  new IDMContext[] { execDmc, group });
            fRegNo = regNo;
            fRegName = regName;
        }
        
        public int getRegNo() { return fRegNo; }
        public String getName() { return fRegName; }

        @Override
        public boolean equals(Object other) {
            return ((super.baseEquals(other)) && (((MIRegisterDMC) other).fRegNo == fRegNo) && 
                    (((MIRegisterDMC) other).fRegName.equals(fRegName)));
        }

        @Override
        public int hashCode() { return super.baseHashCode() ^ fRegNo; }
        @Override
        public String toString() { return baseToString() + ".register[" + fRegNo + "]"; } //$NON-NLS-1$ //$NON-NLS-2$
    }
    
    /*
     *  Event class to notify register value is changed
     */
    public static class RegisterChangedDMEvent implements IRegisters.IRegisterChangedDMEvent {

    	private final IRegisterDMContext fRegisterDmc;
    	
    	RegisterChangedDMEvent(IRegisterDMContext registerDMC) { 
    		fRegisterDmc = registerDMC;
        }
        
		public IRegisterDMContext getDMContext() {
			return fRegisterDmc;
		}
    }
    
    /*
     *  Internal control variables.
     */
    
    private MIRegisterGroupDMC fGeneralRegistersGroupDMC; 
    private CommandCache fRegisterNameCache;	 // Cache for holding the Register Names in the single Group
    private CommandCache fRegisterValueCache;  // Cache for holding the Register Values

    public MIRegisters(DsfSession session) 
    {
        super(session);
    }

    @Override
    protected BundleContext getBundleContext() 
    {
        return MIPlugin.getBundleContext();
    }
    
    @Override
    public void initialize(final RequestMonitor requestMonitor) {
        super.initialize(
            new RequestMonitor(getExecutor(), requestMonitor) { 
                @Override
                protected void handleSuccess() {
                    doInitialize(requestMonitor);
                }});
    }
    
    private void doInitialize(RequestMonitor requestMonitor) {
        /*
         * Create the lower level register cache.
         */
    	ICommandControlService commandControl = getServicesTracker().getService(ICommandControlService.class);
        fRegisterValueCache = new CommandCache(getSession(), commandControl);
        fRegisterValueCache.setContextAvailable(commandControl.getContext(), true);
        fRegisterNameCache  = new CommandCache(getSession(), commandControl);
        fRegisterNameCache.setContextAvailable(commandControl.getContext(), true);
               
        /*
         * Sign up so we see events. We use these events to decide how to manage
         * any local caches we are providing as well as the lower level register
         * cache we create to get/set registers on the target.
         */
        getSession().addServiceEventListener(this, null);
        
        /*
         * Make ourselves known so clients can use us.
         */
        register(new String[]{IRegisters.class.getName(), MIRegisters.class.getName()}, new Hashtable<String,String>());

        requestMonitor.done();
    }

    @Override
    public void shutdown(RequestMonitor requestMonitor) 
    {
        unregister();
        getSession().removeServiceEventListener(this);
        super.shutdown(requestMonitor);
    }

    public boolean isValid() { return true; }
    
    @SuppressWarnings("unchecked")
    public void getModelData(IDMContext dmc, DataRequestMonitor<?> rm) {
        /*
         * This is the method which is called when actual results need to be returned.  We
         * can be called either with a service DMC for which we return ourselves or we can
         * be called with the DMC's we have handed out. If the latter is the case then  we
         * data mine by talking to the Debug Engine.
         */
        
        if (dmc instanceof MIRegisterGroupDMC) {
            getRegisterGroupData((MIRegisterGroupDMC)dmc, (DataRequestMonitor<IRegisterGroupDMData>)rm);
        } else if (dmc instanceof MIRegisterDMC) {
            getRegisterData((MIRegisterDMC)dmc, (DataRequestMonitor<IRegisterDMData>)rm);
        } else if (dmc instanceof FormattedValueDMContext) {
            getFormattedExpressionValue((FormattedValueDMContext)dmc, (DataRequestMonitor<FormattedValueDMData>)rm);
        } else {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, -1, "Unknown DMC type", null));  //$NON-NLS-1$
            rm.done();
        }
    }

    public void getFormattedExpressionValue(FormattedValueDMContext dmc, DataRequestMonitor<FormattedValueDMData> rm) {
        if (dmc.getParents().length == 1 && dmc.getParents()[0] instanceof MIRegisterDMC) {
                getRegisterDataValue( (MIRegisterDMC) dmc.getParents()[0], dmc.getFormatID(), rm);
        } else {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_HANDLE, "Unknown DMC type", null));  //$NON-NLS-1$
            rm.done();
        }
    }
    
    public void getRegisterGroupData(IRegisterGroupDMContext regGroupDmc, DataRequestMonitor<IRegisterGroupDMData> rm) {
        /**
         * For the GDB GDBMI implementation there is only on group. The GPR and FPU registers are grouped into 
         * one set. We are going to hard wire this set as the "General Registers".
         */
        class RegisterGroupData implements IRegisterGroupDMData {
            public String getName() { return "General Registers"; } //$NON-NLS-1$
            public String getDescription() { return "General Purpose and FPU Register Group"; } //$NON-NLS-1$
        }

        rm.setData( new RegisterGroupData() ) ;
        rm.done();
    }

    public void getBitFieldData(IBitFieldDMContext dmc, DataRequestMonitor<IBitFieldDMData> rm) {
        rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, NOT_SUPPORTED, "Bit fields not yet supported", null));  //$NON-NLS-1$
        rm.done();
    }
    
    /**
     * For the GDB GDBMI implementation there is only on group. We represent
     * this group as a single list we maintain within this service. So we
     * need to search this list to see if we have a current value.
     */
    public void getRegisterData(IRegisterDMContext regDmc , final DataRequestMonitor<IRegisterDMData> rm) {
        if (regDmc instanceof MIRegisterDMC) {
            final MIRegisterDMC miRegDmc = (MIRegisterDMC)regDmc;
            IMIExecutionDMContext execDmc = DMContexts.getAncestorOfType(regDmc, IMIExecutionDMContext.class);
            // Create register DMC with name if execution DMC is not present.
            if(execDmc == null){
                rm.setData(new RegisterData(miRegDmc.getName(), BLANK_STRING, false));
                rm.done();
                return;
            }
            
            int[] regnos = {miRegDmc.getRegNo()};
            fRegisterValueCache.execute(
                new MIDataListRegisterValues(execDmc, MIFormat.HEXADECIMAL, regnos),
                new DataRequestMonitor<MIDataListRegisterValuesInfo>(getExecutor(), rm) {
                    @Override
                    protected void handleSuccess() {
                        // Retrieve the register value.
                        MIRegisterValue[] regValue = getData().getMIRegisterValues();
    
                        // If the list is empty just return empty handed.
                        if (regValue.length == 0) {
                            assert false : "Backend protocol error"; //$NON-NLS-1$
                            //done.setStatus(new Status(IStatus.ERROR, IDsfStatusConstants.INTERNAL_ERROR ,));
                            rm.done();
                            return;
                        }
                        
                        // We can determine if the register is floating point because
                        // GDB returns this additional information as part of the value.
                        MIRegisterValue reg = regValue[0];
                        boolean isFloat = false;
                        
                        if ( reg.getValue().contains("float")) { //$NON-NLS-1$
                            isFloat = true;
                        }
    
                        // Return the new register attributes.
                        rm.setData(new RegisterData(miRegDmc.getName(), BLANK_STRING, isFloat));
                        rm.done();
                    }
                });
        } else {
            rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INTERNAL_ERROR, "Unknown DMC type", null));  //$NON-NLS-1$
            rm.done();
        }
    }
    
    private void getRegisterDataValue( final MIRegisterDMC regDmc, final String formatId, final DataRequestMonitor<FormattedValueDMData> rm) {
        IMIExecutionDMContext miExecDmc = DMContexts.getAncestorOfType(regDmc, IMIExecutionDMContext.class);
        if(miExecDmc == null){
            // Set value to blank if execution dmc is not present
            rm.setData( new FormattedValueDMData( BLANK_STRING ) );
            rm.done();
            return;
        }

        // Select the format to be shown
        int NumberFormat = MIFormat.HEXADECIMAL;
        
        if ( HEX_FORMAT.equals    ( formatId ) ) { NumberFormat = MIFormat.HEXADECIMAL; }
        if ( OCTAL_FORMAT.equals  ( formatId ) ) { NumberFormat = MIFormat.OCTAL; }
        if ( NATURAL_FORMAT.equals( formatId ) ) { NumberFormat = MIFormat.NATURAL; }
        if ( BINARY_FORMAT.equals ( formatId ) ) { NumberFormat = MIFormat.BINARY; }
        if ( DECIMAL_FORMAT.equals( formatId ) ) { NumberFormat = MIFormat.DECIMAL; }
        
        int[] regnos = {regDmc.getRegNo()};
        fRegisterValueCache.execute(
            new MIDataListRegisterValues(miExecDmc, NumberFormat, regnos),
            new DataRequestMonitor<MIDataListRegisterValuesInfo>(getExecutor(), rm) {
                @Override
                protected void handleSuccess() {
                    // Retrieve the register value.
                    MIRegisterValue[] regValue = getData().getMIRegisterValues();

                    // If the list is empty just return empty handed.
                    if (regValue.length == 0) {
                        assert false : "Backend protocol error"; //$NON-NLS-1$
                        //done.setStatus(new Status(IStatus.ERROR, IDsfStatusConstants.INTERNAL_ERROR ,));
                        rm.done();
                        return;
                    }

                    MIRegisterValue reg = regValue[0];

                    // Return the new register value.
                    rm.setData( new FormattedValueDMData( reg.getValue() ) );
                    rm.done();
                }
            });
    }
        
    static class RegisterData implements IRegisterDMData {
    	
        final private String fRegName;
        final private String fRegDesc;
        final private boolean fIsFloat;
    	
    	public RegisterData(String regName, String regDesc, boolean isFloat ) {
    		
            fRegName = regName;
            fRegDesc = regDesc;
            fIsFloat = isFloat;
    	}
    	
    	public boolean isReadable() { return true; }
        public boolean isReadOnce() { return false; }
        public boolean isWriteable() { return true; }
        public boolean isWriteOnce() { return false; }
        public boolean hasSideEffects() { return false; }
        public boolean isVolatile() { return true; }

        public boolean isFloat() { return fIsFloat; }
        public String getName() { return fRegName; }
        public String getDescription() { return fRegDesc; }
    }

    // Wraps a list of registers in DMContexts.
    private MIRegisterDMC[] makeRegisterDMCs(MIRegisterGroupDMC groupDmc, String[] regNames) {
    	return makeRegisterDMCs(groupDmc, null, regNames);
    }
    
    // Wraps a list of registers in DMContexts.
    private MIRegisterDMC[] makeRegisterDMCs(MIRegisterGroupDMC groupDmc, IMIExecutionDMContext execDmc, String[] regNames) {
        MIRegisterDMC[] regDmcList = new MIRegisterDMC[regNames.length];
        int regNo = 0 ;
        for (String regName : regNames) {
        	if(execDmc != null)
        		regDmcList[regNo] = new MIRegisterDMC(this, groupDmc, execDmc, regNo, regName);
        	else
        		regDmcList[regNo] = new MIRegisterDMC(this, groupDmc, regNo, regName);
            regNo++;
        }
        
        return regDmcList;
    }

    /*
     *   Event handling section. These event handlers control the caching state of the
     *   register caches. This service creates several cache objects. Not all of which
     *   need to be flushed. These handlers maintain the state of the caches.
     */

    /**
     * @nooverride This method is not intended to be re-implemented or extended by clients.
     * @noreference This method is not intended to be referenced by clients.
     */
    @DsfServiceEventHandler 
    public void eventDispatched(IRunControl.IResumedDMEvent e) {
        fRegisterValueCache.setContextAvailable(e.getDMContext(), false);
        if (e.getReason() != StateChangeReason.STEP) {
            fRegisterValueCache.reset();
        }
    }
    
    /**
     * @nooverride This method is not intended to be re-implemented or extended by clients.
     * @noreference This method is not intended to be referenced by clients.
     */
    @DsfServiceEventHandler 
    public void eventDispatched(
    IRunControl.ISuspendedDMEvent e) {
        fRegisterValueCache.setContextAvailable(e.getDMContext(), true);
        fRegisterValueCache.reset();
    }

    /**
     * @nooverride This method is not intended to be re-implemented or extended by clients.
     * @noreference This method is not intended to be referenced by clients.
     */
    @DsfServiceEventHandler 
    public void eventDispatched(final IRegisters.IRegisterChangedDMEvent e) {
    	fRegisterValueCache.reset();
    }
    
    private void generateRegisterChangedEvent(IRegisterDMContext dmc ) {
        getSession().dispatchEvent(new RegisterChangedDMEvent(dmc), getProperties());
    }
    
    /*
     * These are the public interfaces for this service.
     * 
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.debug.service.IRegisters#getRegisterGroups(org.eclipse.dd.dsf.debug.service.IRunControl.IExecutionDMContext, org.eclipse.dd.dsf.debug.service.IStack.IFrameDMContext, org.eclipse.dd.dsf.concurrent.DataRequestMonitor)
     */
    public void getRegisterGroups(IDMContext ctx, DataRequestMonitor<IRegisterGroupDMContext[]> rm ) {
    	IContainerDMContext contDmc = DMContexts.getAncestorOfType(ctx, IContainerDMContext.class);
        if (contDmc == null) {
            rm.setStatus( new Status( IStatus.ERROR , MIPlugin.PLUGIN_ID , INVALID_HANDLE , "Container context not found", null ) ) ;   //$NON-NLS-1$
            rm.done();
            return;
        }
        
        if (fGeneralRegistersGroupDMC == null) {
            fGeneralRegistersGroupDMC = new MIRegisterGroupDMC( this , contDmc, 0 , "General Registers" ) ;  //$NON-NLS-1$
        }
        MIRegisterGroupDMC[] groupDMCs = new MIRegisterGroupDMC[] { fGeneralRegistersGroupDMC };
        rm.setData(groupDMCs) ;
        rm.done() ;
    }
    
    
    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.debug.service.IRegisters#getRegisters(org.eclipse.dd.dsf.debug.service.IRegisters.IRegisterGroupDMContext, org.eclipse.dd.dsf.concurrent.DataRequestMonitor)
     */
    public void getRegisters(final IDMContext dmc, final DataRequestMonitor<IRegisterDMContext[]> rm) {
    	final MIRegisterGroupDMC groupDmc = DMContexts.getAncestorOfType(dmc, MIRegisterGroupDMC.class);
        if ( groupDmc == null ) { 
            rm.setStatus( new Status( IStatus.ERROR , MIPlugin.PLUGIN_ID , INVALID_HANDLE , "RegisterGroup context not found", null ) ) ;   //$NON-NLS-1$
            rm.done();
            return;
        }

        final IContainerDMContext containerDmc = DMContexts.getAncestorOfType(dmc, IContainerDMContext.class);
        if ( containerDmc == null ) { 
            rm.setStatus( new Status( IStatus.ERROR , MIPlugin.PLUGIN_ID , INVALID_HANDLE , "Container context not found" , null ) ) ;   //$NON-NLS-1$
            rm.done();
            return;
        }

        // There is only one group and its number must be 0.
        if ( groupDmc.getGroupNo() == 0 ) {
        	final IMIExecutionDMContext executionDmc = DMContexts.getAncestorOfType(dmc, IMIExecutionDMContext.class);
        	fRegisterNameCache.execute(
                new MIDataListRegisterNames(containerDmc),
                new DataRequestMonitor<MIDataListRegisterNamesInfo>(getExecutor(), rm) { 
                    @Override
                    protected void handleSuccess() {
                        // Retrieve the register names.
                        String[] regNames = getData().getRegisterNames() ;
                       
                        // If the list is empty just return empty handed.
                        if ( regNames.length == 0 ) {
                            rm.done();
                            return;
                        }
                        // Create DMContexts for each of the register names.
                        if(executionDmc == null)
                        	rm.setData(makeRegisterDMCs(groupDmc, regNames));
                        else
                        	rm.setData(makeRegisterDMCs(groupDmc, executionDmc, regNames));
                        rm.done();
                    }
                });
        } else {
            rm.setStatus(new Status(IStatus.ERROR , MIPlugin.PLUGIN_ID , INTERNAL_ERROR , "Invalid group = " + groupDmc , null)); //$NON-NLS-1$
            rm.done();
        }
    }
    
    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.debug.service.IRegisters#getBitFields(org.eclipse.dd.dsf.debug.service.IRegisters.IRegisterDMContext, org.eclipse.dd.dsf.concurrent.DataRequestMonitor)
     */
    public void getBitFields( IDMContext regDmc , DataRequestMonitor<IBitFieldDMContext[]> rm ) {
        rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, NOT_SUPPORTED, "BitField not supported", null)); //$NON-NLS-1$
        rm.done();
    }

    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.debug.service.IRegisters#writeRegister(org.eclipse.dd.dsf.debug.service.IRegisters.IRegisterDMContext, java.lang.String, java.lang.String, org.eclipse.dd.dsf.concurrent.RequestMonitor)
     */
    public void writeRegister(IRegisterDMContext regCtx, final String regValue, final String formatId, final RequestMonitor rm) {
      MIRegisterGroupDMC grpDmc = DMContexts.getAncestorOfType(regCtx, MIRegisterGroupDMC.class);
      if ( grpDmc == null ) { 
          rm.setStatus( new Status( IStatus.ERROR , MIPlugin.PLUGIN_ID , INVALID_HANDLE , "RegisterGroup context not found" , null ) ) ;   //$NON-NLS-1$
          rm.done();
          return;
      }
	  
      final MIRegisterDMC regDmc = (MIRegisterDMC)regCtx;
	  // There is only one group and its number must be 0.
	  if ( grpDmc.getGroupNo() == 0 ) {
	  	final ExpressionService exprService = getServicesTracker().getService(ExpressionService.class);
	  	String regName = regDmc.getName();
	      final IExpressionDMContext exprCtxt = exprService.createExpression(regCtx, "$" + regName); //$NON-NLS-1$
	      exprService.getModelData(exprCtxt, new DataRequestMonitor<IExpressionDMData>(getExecutor(), rm) {
				@Override
				protected void handleSuccess() {
					// Evaluate the expression - request HEX since it works in every case 
					final FormattedValueDMContext valueDmc = exprService.getFormattedValueContext(exprCtxt, formatId);
					exprService.getModelData(
	              	valueDmc, 
	                  new DataRequestMonitor<FormattedValueDMData>(getExecutor(), rm) {
	          			@Override
	          			protected void handleSuccess() {
	          				if(! regValue.equals(getData().getFormattedValue()) || ! valueDmc.getFormatID().equals(formatId)){
		          	            exprService.writeExpression(exprCtxt, regValue, formatId, new DataRequestMonitor<MIInfo>(getExecutor(), rm) {
		          	                @Override
		          	                protected void handleSuccess() {
		          	                	generateRegisterChangedEvent(regDmc);
		          	                	rm.done();
		          	                }
		          	            });
	          				}//if
	          				else {
	          					rm.done();
	          				}
	          			}//handleOK
	              	}
	              );
				}
			});            
	  }
	  else {
        rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, NOT_SUPPORTED, "Invalid group = " + grpDmc, null)); //$NON-NLS-1$
        rm.done();
	  } 
    }

    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.debug.service.IRegisters#writeBitField(org.eclipse.dd.dsf.debug.service.IRegisters.IBitFieldDMContext, java.lang.String, java.lang.String, org.eclipse.dd.dsf.concurrent.RequestMonitor)
     */
    public void writeBitField(IBitFieldDMContext bitFieldCtx, String bitFieldValue, String formatId, RequestMonitor rm) {
        rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, NOT_SUPPORTED, "Writing bit field not supported", null)); //$NON-NLS-1$
        rm.done();
    }
    
    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.debug.service.IRegisters#writeBitField(org.eclipse.dd.dsf.debug.service.IRegisters.IBitFieldDMContext, org.eclipse.dd.dsf.debug.service.IRegisters.IMnemonic, org.eclipse.dd.dsf.concurrent.RequestMonitor)
     */
    public void writeBitField(IBitFieldDMContext bitFieldCtx, IMnemonic mnemonic, RequestMonitor rm) {
        rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, NOT_SUPPORTED, "Writing bit field not supported", null)); //$NON-NLS-1$
        rm.done();
    }

    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.debug.service.IFormattedValues#getAvailableFormats(org.eclipse.dd.dsf.debug.service.IFormattedValues.IFormattedDataDMContext, org.eclipse.dd.dsf.concurrent.DataRequestMonitor)
     */
    public void getAvailableFormats(IFormattedDataDMContext dmc, DataRequestMonitor<String[]> rm) {
        
        rm.setData(new String[] { HEX_FORMAT, DECIMAL_FORMAT, OCTAL_FORMAT, BINARY_FORMAT, NATURAL_FORMAT });
        rm.done();
    }

    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.debug.service.IFormattedValues#getFormattedValueContext(org.eclipse.dd.dsf.debug.service.IFormattedValues.IFormattedDataDMContext, java.lang.String)
     */
    public FormattedValueDMContext getFormattedValueContext(IFormattedDataDMContext dmc, String formatId) {
        if ( dmc instanceof MIRegisterDMC ) {
            MIRegisterDMC regDmc = (MIRegisterDMC) dmc;
            return( new FormattedValueDMContext( this, regDmc, formatId));
        }
        return null;
    }

    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.debug.service.IRegisters#findRegisterGroup(org.eclipse.dd.dsf.datamodel.IDMContext, java.lang.String, org.eclipse.dd.dsf.concurrent.DataRequestMonitor)
     */
    public void findRegisterGroup(IDMContext ctx, String name, DataRequestMonitor<IRegisterGroupDMContext> rm) {
        rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, NOT_SUPPORTED, "Finding a Register Group context not supported", null)); //$NON-NLS-1$
        rm.done();
    }
    
    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.debug.service.IRegisters#findRegister(org.eclipse.dd.dsf.datamodel.IDMContext, java.lang.String, org.eclipse.dd.dsf.concurrent.DataRequestMonitor)
     */
    public void findRegister(IDMContext ctx, String name, DataRequestMonitor<IRegisterDMContext> rm) {
        rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, NOT_SUPPORTED, "Finding a Register context not supported", null)); //$NON-NLS-1$
        rm.done();
    }

    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.debug.service.IRegisters#findBitField(org.eclipse.dd.dsf.datamodel.IDMContext, java.lang.String, org.eclipse.dd.dsf.concurrent.DataRequestMonitor)
     */
    public void findBitField(IDMContext ctx, String name, DataRequestMonitor<IBitFieldDMContext> rm) {
        rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, NOT_SUPPORTED, "Finding a Register Group context not supported", null)); //$NON-NLS-1$
        rm.done();
    }
    
    /**
     * {@inheritDoc}
     * @since 1.1
     */
    public void flushCache(IDMContext context) {
        fRegisterNameCache.reset(context);
        fRegisterValueCache.reset(context);
    }
}
