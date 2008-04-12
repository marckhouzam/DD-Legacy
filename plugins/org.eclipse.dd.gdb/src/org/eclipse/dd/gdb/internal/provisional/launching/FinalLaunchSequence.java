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
package org.eclipse.dd.gdb.internal.provisional.launching;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.cdt.debug.core.ICDTLaunchConfigurationConstants;
import org.eclipse.cdt.debug.internal.core.sourcelookup.CSourceLookupDirector;
import org.eclipse.cdt.debug.mi.core.IGDBServerMILaunchConfigurationConstants;
import org.eclipse.cdt.debug.mi.core.IMILaunchConfigurationConstants;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.DsfExecutor;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.concurrent.Sequence;
import org.eclipse.dd.dsf.debug.service.IBreakpoints.IBreakpointsTargetDMContext;
import org.eclipse.dd.dsf.debug.service.IRunControl.IContainerDMContext;
import org.eclipse.dd.dsf.service.DsfServicesTracker;
import org.eclipse.dd.gdb.internal.GdbPlugin;
import org.eclipse.dd.gdb.internal.provisional.service.command.GDBControl;
import org.eclipse.dd.gdb.internal.provisional.service.command.GDBControl.SessionType;
import org.eclipse.dd.mi.service.CSourceLookup;
import org.eclipse.dd.mi.service.MIBreakpointsManager;
import org.eclipse.dd.mi.service.command.commands.CLISource;
import org.eclipse.dd.mi.service.command.commands.MIBreakInsert;
import org.eclipse.dd.mi.service.command.commands.MICommand;
import org.eclipse.dd.mi.service.command.commands.MIExecContinue;
import org.eclipse.dd.mi.service.command.commands.MIExecRun;
import org.eclipse.dd.mi.service.command.commands.MIFileExecFile;
import org.eclipse.dd.mi.service.command.commands.MIFileSymbolFile;
import org.eclipse.dd.mi.service.command.commands.MIGDBSetAutoSolib;
import org.eclipse.dd.mi.service.command.commands.MIGDBSetSolibSearchPath;
import org.eclipse.dd.mi.service.command.commands.MIInferiorTTYSet;
import org.eclipse.dd.mi.service.command.commands.MITargetSelect;
import org.eclipse.dd.mi.service.command.output.MIBreakInsertInfo;
import org.eclipse.dd.mi.service.command.output.MIInfo;
import org.eclipse.debug.core.DebugException;

public class FinalLaunchSequence extends Sequence {

    Step[] fSteps = new Step[] {
        /*
         * Fetch the control service for later use
         */
        new Step() { @Override
        public void execute(RequestMonitor requestMonitor) {
            DsfServicesTracker tracker = new DsfServicesTracker(GdbPlugin.getBundleContext(), fLaunch.getSession().getId());
            fCommandControl = tracker.getService(GDBControl.class);
            tracker.dispose();

            requestMonitor.done();
        }},
    	/*
    	 * Specify connection of inferior input/output with a terminal.
    	 */
        new Step() { @Override
        public void execute(RequestMonitor requestMonitor) {
        	try {
        		boolean useTerminal = fLaunch.getLaunchConfiguration().getAttribute(ICDTLaunchConfigurationConstants.ATTR_USE_TERMINAL, true);
        		
        		if (useTerminal) {
            		fCommandControl.queueCommand(
         				new MIInferiorTTYSet(fCommandControl.getControlDMContext(), fCommandControl.getPtyName()), 
     					new DataRequestMonitor<MIInfo>(getExecutor(), requestMonitor));
        		} else {
        			requestMonitor.done();
        		}
        	} catch (CoreException e) {
        		requestMonitor.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, -1, "Cannot get terminal option", e)); //$NON-NLS-1$
        		requestMonitor.done();
        	}
        }},
    	/*
    	 * Source the gdbinit file specified in the launch
    	 */
        new Step() { @Override
        public void execute(final RequestMonitor requestMonitor) {
        	try {
        		final String gdbinitFile = fLaunch.getLaunchConfiguration().getAttribute(IMILaunchConfigurationConstants.ATTR_GDB_INIT, 
        				                                                           IMILaunchConfigurationConstants.DEBUGGER_GDB_INIT_DEFAULT );
        		if (gdbinitFile != null && gdbinitFile.length() > 0) {
        			fCommandControl.queueCommand(
        					new CLISource(fCommandControl.getControlDMContext(), gdbinitFile), 
        					new DataRequestMonitor<MIInfo>(getExecutor(), requestMonitor) {
        						@Override
        						protected void handleCompleted() {
        							// If the gdbinitFile is the default, then it may not exist and we
        							// should not consider this an error.
        							// If it is not the default, then the user must have specified it and
        							// we want to warn the user if we can't find it.
        							if (!gdbinitFile.equals(IMILaunchConfigurationConstants.DEBUGGER_GDB_INIT_DEFAULT )) {
        								requestMonitor.setStatus(getStatus());
        							}
        							requestMonitor.done();
        						}
        					});
        		} else {
        			requestMonitor.done();
        		}
        	} catch (CoreException e) {
        		requestMonitor.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, -1, "Cannot get gdbinit option", e)); //$NON-NLS-1$
        		requestMonitor.done();
        	}
        }},
    	/*
    	 * Specify the executable file to be debugged.
    	 */
        new Step() { @Override
        public void execute(RequestMonitor requestMonitor) {
            fCommandControl.queueCommand(
           		new MIFileExecFile(fCommandControl.getControlDMContext(), 
            			           fCommandControl.getExecutablePath().toOSString()), 
           	    new DataRequestMonitor<MIInfo>(getExecutor(), requestMonitor));
        }},
    	/*
    	 * Read symbol table.
    	 */
        new Step() { @Override
        public void execute(RequestMonitor requestMonitor) {
            fCommandControl.queueCommand(
               	new MIFileSymbolFile(fCommandControl.getControlDMContext(), 
                		             fCommandControl.getExecutablePath().toOSString()), 
               	new DataRequestMonitor<MIInfo>(getExecutor(), requestMonitor));
        }},
        /*
         * Tell GDB to automatically load or not the shared library symbols
         */
        new Step() { @Override
        public void execute(RequestMonitor requestMonitor) {
    		try {
    			boolean autolib = fLaunch.getLaunchConfiguration().getAttribute(IMILaunchConfigurationConstants.ATTR_DEBUGGER_AUTO_SOLIB,
    					                                                        IMILaunchConfigurationConstants.DEBUGGER_AUTO_SOLIB_DEFAULT);
                fCommandControl.queueCommand(
                	new MIGDBSetAutoSolib(fCommandControl.getControlDMContext(), autolib), 
                	new DataRequestMonitor<MIInfo>(getExecutor(), requestMonitor));
    		} catch (CoreException e) {
    			requestMonitor.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, -1, "Cannot set shared library option", e)); //$NON-NLS-1$
    			requestMonitor.done();
    		}
        }},
        /*
         * Set the shared library paths
         */
        new Step() { @Override
        public void execute(RequestMonitor requestMonitor) {
      		try {
      		    @SuppressWarnings("unchecked")
    			List<String> p = fLaunch.getLaunchConfiguration().getAttribute(IMILaunchConfigurationConstants.ATTR_DEBUGGER_SOLIB_PATH, 
    					                                                       new ArrayList<String>(1));
   				if (p.size() > 0) {
   					String[] paths = p.toArray(new String[p.size()]);
   	                fCommandControl.queueCommand(
   	                	new MIGDBSetSolibSearchPath(fCommandControl.getControlDMContext(), paths), 
   	                	new DataRequestMonitor<MIInfo>(getExecutor(), requestMonitor));
   				} else {
   	                requestMonitor.done();
   				}
    		} catch (CoreException e) {
                requestMonitor.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, -1, "Cannot set share library paths", e)); //$NON-NLS-1$
                requestMonitor.done();
    		}
    	}},
    	/*
    	 * Setup the source paths
    	 */
        new Step() { @Override
        public void execute(RequestMonitor requestMonitor) {
            DsfServicesTracker tracker = new DsfServicesTracker(GdbPlugin.getBundleContext(), fLaunch.getSession().getId());
            CSourceLookup sourceLookup = tracker.getService(CSourceLookup.class);
            tracker.dispose();

            CSourceLookupDirector locator = (CSourceLookupDirector)fLaunch.getSourceLocator();
            sourceLookup.setSourceLookupPath(fCommandControl.getGDBDMContext(), 
               	                             locator.getSourceContainers(), requestMonitor);
        }},
        /* 
         * If remote debugging, connect to target 
         */
        new Step() {
        	private boolean fTcpConnection;
            private String fRemoteTcpHost;
            private String fRemoteTcpPort;
            private String fSerialDevice;
            
            private boolean checkConnectionType(RequestMonitor requestMonitor) {
                try {
                	fTcpConnection = fLaunch.getLaunchConfiguration().getAttribute(
                                    IGDBServerMILaunchConfigurationConstants.ATTR_REMOTE_TCP,
                                    false);
                } catch (CoreException e) {
                    requestMonitor.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, -1, "Cannot retrieve connection mode", e)); //$NON-NLS-1$
                    requestMonitor.done();
                    return false;
                }
                return true;
            }
            
            private boolean getSerialDevice(RequestMonitor requestMonitor) {
                try {
                    fSerialDevice = fLaunch.getLaunchConfiguration().getAttribute(
                                    			IGDBServerMILaunchConfigurationConstants.ATTR_DEV, "invalid"); //$NON-NLS-1$
                } catch (CoreException e) {
                    requestMonitor.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, -1, "Cannot retrieve serial device", e)); //$NON-NLS-1$
                    requestMonitor.done();
                    return false;
                }
                return true;
            }
            
            private boolean getTcpHost(RequestMonitor requestMonitor) {
                try {
                    fRemoteTcpHost = fLaunch.getLaunchConfiguration().getAttribute(
                    							IGDBServerMILaunchConfigurationConstants.ATTR_HOST, "invalid"); //$NON-NLS-1$
                } catch (CoreException e) {
                    requestMonitor.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, -1, "Cannot retrieve remote TCP host", e)); //$NON-NLS-1$
                    requestMonitor.done();
                    return false;
                }
                return true;
            }

            private boolean getTcpPort(RequestMonitor requestMonitor) {
                try {
                    fRemoteTcpPort = fLaunch.getLaunchConfiguration().getAttribute(
                                    			IGDBServerMILaunchConfigurationConstants.ATTR_PORT, "invalid"); //$NON-NLS-1$
                } catch (CoreException e) {
                    requestMonitor.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, -1, "Cannot retrieve remote TCP port", e)); //$NON-NLS-1$
                    requestMonitor.done();
                    return false;
                }
                return true;
            }

            @Override
            public void execute(final RequestMonitor requestMonitor) {
               	if (fSessionType == SessionType.REMOTE) {
               		if (!checkConnectionType(requestMonitor)) return;
               		
               		if (fTcpConnection) {
                   		if (!getTcpHost(requestMonitor)) return;
                        if (!getTcpPort(requestMonitor)) return;
                    
                        fCommandControl.queueCommand(
                        		new MITargetSelect(fCommandControl.getControlDMContext(), 
                        				           fRemoteTcpHost, fRemoteTcpPort), 
                        	    new DataRequestMonitor<MIInfo>(getExecutor(), requestMonitor));
               		} else {
               			if (!getSerialDevice(requestMonitor)) return;
                    
                        fCommandControl.queueCommand(
                        		new MITargetSelect(fCommandControl.getControlDMContext(), 
                        				           fSerialDevice), 
                        	    new DataRequestMonitor<MIInfo>(getExecutor(), requestMonitor));
               		}
            	} else {
            		requestMonitor.done();
            	}
 
            }
        },
        /* 
         * Start tracking the breakpoints once we know we are connected to the target (necessary for remote debugging) 
         */
        new Step() { @Override
        public void execute(final RequestMonitor requestMonitor) {
            DsfServicesTracker tracker = new DsfServicesTracker(GdbPlugin.getBundleContext(), fLaunch.getSession().getId());
            MIBreakpointsManager bpmService = tracker.getService(MIBreakpointsManager.class);
            tracker.dispose();
        	bpmService.startTrackingBreakpoints(fCommandControl.getGDBDMContext(), requestMonitor);
        }},
        /*
         * If needed, insert breakpoint at main and run to it.
         */
        new Step() {
            private boolean fStopInMain = false;
            private String fStopSymbol = null;

            /**
             * @return The return value actually indicates whether the get operation succeeded, 
             * not whether to stop.
             */
            private boolean readStopAtMain(RequestMonitor requestMonitor) {
                try {
                    fStopInMain = fLaunch.getLaunchConfiguration().getAttribute( ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_STOP_AT_MAIN, false );
                } catch (CoreException e) {
                    requestMonitor.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, -1, "Cannot retrieve the entry point symbol", e)); //$NON-NLS-1$
                    requestMonitor.done();
                    return false;
                }
                return true;
            }
            
            private boolean readStopSymbol(RequestMonitor requestMonitor) {
                try {
                    fStopSymbol = fLaunch.getLaunchConfiguration().getAttribute( ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_STOP_AT_MAIN_SYMBOL, ICDTLaunchConfigurationConstants.DEBUGGER_STOP_AT_MAIN_SYMBOL_DEFAULT );
                } catch (CoreException e) {
                    requestMonitor.setStatus(new Status(IStatus.ERROR, GdbPlugin.PLUGIN_ID, DebugException.CONFIGURATION_INVALID, "Cannot retrieve the entry point symbol", e)); //$NON-NLS-1$
                    requestMonitor.done();
                    return false;
                }                
                return true;
            }
            
            @Override
            public void execute(final RequestMonitor requestMonitor) {
            	final MICommand<MIInfo> execCommand;
            	if (fSessionType == SessionType.REMOTE) {
            		// When doing remote debugging, we use -exec-continue instead of -exec-run 
            	    execCommand = new MIExecContinue((IContainerDMContext)fCommandControl.getControlDMContext());
            	} else {
            		execCommand = new MIExecRun((IContainerDMContext)fCommandControl.getControlDMContext(), new String[0]);	
            	}
            	
                if (!readStopAtMain(requestMonitor)) return;
                if (!fStopInMain) {
                	// Just start the program.
    				fCommandControl.queueCommand(execCommand, new DataRequestMonitor<MIInfo>(getExecutor(), requestMonitor));
                } else {
                    if (!readStopSymbol(requestMonitor)) return;
                
                    // Insert a breakpoint at the requested stop symbol.
                    fCommandControl.queueCommand(
                    		new MIBreakInsert(
                    				(IBreakpointsTargetDMContext)fCommandControl.getControlDMContext(), 
                    				true, false, null, 0, fStopSymbol, 0), 
                    				new DataRequestMonitor<MIBreakInsertInfo>(getExecutor(), requestMonitor) { 
                    			@Override
                    			protected void handleSuccess() {

                    				// After the break-insert is done, execute the -exec-run or -exec-continue command.
                    				fCommandControl.queueCommand(execCommand, new DataRequestMonitor<MIInfo>(getExecutor(), requestMonitor));
                    			}
                    		});
                }
            }
        },
    };

    GdbLaunch fLaunch;
    SessionType fSessionType;

    GDBControl fCommandControl;

    public FinalLaunchSequence(DsfExecutor executor, GdbLaunch launch, SessionType type) {
        super(executor);
        fLaunch = launch;
        fSessionType = type;   
    }
    
    
    @Override
    public Step[] getSteps() {
        return fSteps;
    }

}

