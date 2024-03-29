/*******************************************************************************
 * Copyright (c) 2008 Ericsson and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Ericsson - initial API and implementation
 *******************************************************************************/

package org.eclipse.dd.mi.service;

import java.math.BigInteger;
import java.util.Hashtable;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.debug.service.IDisassembly;
import org.eclipse.dd.dsf.debug.service.IInstruction;
import org.eclipse.dd.dsf.debug.service.IMixedInstruction;
import org.eclipse.dd.dsf.debug.service.command.ICommandControl;
import org.eclipse.dd.dsf.service.AbstractDsfService;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.mi.internal.MIPlugin;
import org.eclipse.dd.mi.service.command.commands.MIDataDisassemble;
import org.eclipse.dd.mi.service.command.output.MIDataDisassembleInfo;
import org.osgi.framework.BundleContext;

public class MIDisassembly extends AbstractDsfService implements IDisassembly {

    // Services
    ICommandControl fConnection;

    ///////////////////////////////////////////////////////////////////////////
    // AbstractDsfService
    ///////////////////////////////////////////////////////////////////////////
    
    /**
     * The service constructor
     * 
     * @param session The debugging session
     */
    public MIDisassembly(DsfSession session) {
        super(session);
    }

    /* (non-Javadoc)
     * @see org.eclipse.dd.dsf.service.AbstractDsfService#initialize(org.eclipse.dd.dsf.concurrent.RequestMonitor)
     */
    @Override
    public void initialize(final RequestMonitor rm) {
        super.initialize(new RequestMonitor(getExecutor(), rm) {
            @Override
            protected void handleSuccess() {
                doInitialize(rm);
            }
        });
    }

    private void doInitialize(final RequestMonitor rm) {
        fConnection = getServicesTracker().getService(ICommandControl.class);
//        getSession().addServiceEventListener(this, null);
        register(new String[] { IDisassembly.class.getName(), MIDisassembly.class.getName() },
                new Hashtable<String, String>());
        rm.done();
    }

    /* (non-Javadoc)
     * @see org.eclipse.dd.dsf.service.AbstractDsfService#shutdown(org.eclipse.dd.dsf.concurrent.RequestMonitor)
     */
    @Override
    public void shutdown(RequestMonitor rm) {
        unregister();
//        getSession().removeServiceEventListener(this);
        rm.done();
    }

    /* (non-Javadoc)
     * @see org.eclipse.dd.dsf.service.AbstractDsfService#getBundleContext()
     */
    @Override
    protected BundleContext getBundleContext() {
        return MIPlugin.getBundleContext();
    }

    ///////////////////////////////////////////////////////////////////////////
    // IDisassembly
    ///////////////////////////////////////////////////////////////////////////
    
    /* (non-Javadoc)
     * @see org.eclipse.dd.dsf.debug.service.IDisassembly#getInstructions(org.eclipse.dd.dsf.debug.service.IDisassembly.IDisassemblyDMContext, java.math.BigInteger, java.math.BigInteger, org.eclipse.dd.dsf.concurrent.DataRequestMonitor)
     */
    public void getInstructions(IDisassemblyDMContext context,
            BigInteger startAddress, BigInteger endAddress,
            final DataRequestMonitor<IInstruction[]> drm)
    {
        // Validate the context
        if (context == null) {
            drm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INTERNAL_ERROR, "Unknown context type", null)); //$NON-NLS-1$);
            drm.done();            
            return;
        }

        // Go for it
        String start = (startAddress != null) ? startAddress.toString() : "$pc";       //$NON-NLS-1$
        String end   = (endAddress   != null) ? endAddress.toString()   : "$pc + 100"; //$NON-NLS-1$
        fConnection.queueCommand(new MIDataDisassemble(context, start, end, false),
            new DataRequestMonitor<MIDataDisassembleInfo>(getExecutor(), drm) {
                @Override
                protected void handleSuccess() {
                    IInstruction[] result = getData().getMIAssemblyCode();
                    drm.setData(result);
                    drm.done();
                }
            });
    }

    /* (non-Javadoc)
     * @see org.eclipse.dd.dsf.debug.service.IDisassembly#getInstructions(org.eclipse.dd.dsf.debug.service.IDisassembly.IDisassemblyDMContext, java.lang.String, int, int, org.eclipse.dd.dsf.concurrent.DataRequestMonitor)
     */
    public void getInstructions(IDisassemblyDMContext context, String filename,
            int linenum, int lines, final DataRequestMonitor<IInstruction[]> drm)
    {
        // Validate the context
        if (context == null) {
            drm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INTERNAL_ERROR, "Unknown context type", null)); //$NON-NLS-1$);
            drm.done();            
            return;
        }

        // Go for it
        fConnection.queueCommand(new MIDataDisassemble(context, filename, linenum, lines, false),
            new DataRequestMonitor<MIDataDisassembleInfo>(getExecutor(), drm) {
                @Override
                protected void handleSuccess() {
                    IInstruction[] result = getData().getMIAssemblyCode();
                    drm.setData(result);
                    drm.done();
                }
            });
    }

    /* (non-Javadoc)
     * @see org.eclipse.dd.dsf.debug.service.IDisassembly#getMixedInstructions(org.eclipse.dd.dsf.debug.service.IDisassembly.IDisassemblyDMContext, java.math.BigInteger, java.math.BigInteger, org.eclipse.dd.dsf.concurrent.DataRequestMonitor)
     */
    public void getMixedInstructions(IDisassemblyDMContext context,
            BigInteger startAddress, BigInteger endAddress,
            final DataRequestMonitor<IMixedInstruction[]> drm)
    {
        // Validate the context
        if (context == null) {
            drm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INTERNAL_ERROR, "Unknown context type", null)); //$NON-NLS-1$);
            drm.done();            
            return;
        }

        // Go for it
        String start = (startAddress != null) ? startAddress.toString() : "$pc";       //$NON-NLS-1$
        String end   = (endAddress   != null) ? endAddress.toString()   : "$pc + 100"; //$NON-NLS-1$
        fConnection.queueCommand(new MIDataDisassemble(context, start, end, true),
            new DataRequestMonitor<MIDataDisassembleInfo>(getExecutor(), drm) {
                @Override
                protected void handleSuccess() {
                    IMixedInstruction[] result = getData().getMIMixedCode();
                    drm.setData(result);
                    drm.done();
                }
            });
    }

    /* (non-Javadoc)
     * @see org.eclipse.dd.dsf.debug.service.IDisassembly#getMixedInstructions(org.eclipse.dd.dsf.debug.service.IDisassembly.IDisassemblyDMContext, java.lang.String, int, int, org.eclipse.dd.dsf.concurrent.DataRequestMonitor)
     */
    public void getMixedInstructions(IDisassemblyDMContext context,
            String filename, int linenum, int lines,
            final DataRequestMonitor<IMixedInstruction[]> drm)
    {
        // Validate the context
        if (context == null) {
            drm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INTERNAL_ERROR, "Unknown context type", null)); //$NON-NLS-1$);
            drm.done();            
            return;
        }

        // Go for it
        fConnection.queueCommand(new MIDataDisassemble(context, filename, linenum, lines, true),
            new DataRequestMonitor<MIDataDisassembleInfo>(getExecutor(), drm) {
                @Override
                protected void handleSuccess() {
                    IMixedInstruction[] result = getData().getMIMixedCode();
                    drm.setData(result);
                    drm.done();
                }
            });
    }

}
