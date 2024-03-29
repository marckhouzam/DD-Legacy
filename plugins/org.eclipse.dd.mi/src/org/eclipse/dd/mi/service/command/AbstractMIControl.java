/*******************************************************************************
 * Copyright (c) 2006, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *     Ericsson 		  - Modified for handling of multiple stacks and threads
 *     Nokia - create and use backend service. 
 *******************************************************************************/
package org.eclipse.dd.mi.service.command;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.DsfRunnable;
import org.eclipse.dd.dsf.datamodel.DMContexts;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.dsf.debug.service.IRunControl;
import org.eclipse.dd.dsf.debug.service.IStack.IFrameDMContext;
import org.eclipse.dd.dsf.debug.service.command.ICommand;
import org.eclipse.dd.dsf.debug.service.command.ICommandControlService;
import org.eclipse.dd.dsf.debug.service.command.ICommandListener;
import org.eclipse.dd.dsf.debug.service.command.ICommandResult;
import org.eclipse.dd.dsf.debug.service.command.ICommandToken;
import org.eclipse.dd.dsf.debug.service.command.IEventListener;
import org.eclipse.dd.dsf.service.AbstractDsfService;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.mi.internal.MIPlugin;
import org.eclipse.dd.mi.service.IMIExecutionDMContext;
import org.eclipse.dd.mi.service.command.commands.MICommand;
import org.eclipse.dd.mi.service.command.commands.MIStackSelectFrame;
import org.eclipse.dd.mi.service.command.commands.MIThreadSelect;
import org.eclipse.dd.mi.service.command.output.MIConst;
import org.eclipse.dd.mi.service.command.output.MIInfo;
import org.eclipse.dd.mi.service.command.output.MIList;
import org.eclipse.dd.mi.service.command.output.MIOOBRecord;
import org.eclipse.dd.mi.service.command.output.MIOutput;
import org.eclipse.dd.mi.service.command.output.MIParser;
import org.eclipse.dd.mi.service.command.output.MIResult;
import org.eclipse.dd.mi.service.command.output.MIResultRecord;
import org.eclipse.dd.mi.service.command.output.MIValue;

/**
 * Base implementation of an MI control service.  It provides basic handling 
 * of input/output channels, and processing of the commands.
 * <p>
 * Extending classes need to implement the initialize() and shutdown() methods.
 */
public abstract class AbstractMIControl extends AbstractDsfService
    implements ICommandControlService
{
    /*
	 *  Thread control variables for the transmit and receive threads.
	 */

	private TxThread fTxThread;
    private RxThread fRxThread;
    
    // MI did not always support the --thread/--frame options
    // This boolean is used to know if we should use -thread-select and -stack-select-frame instead
    private boolean fUseThreadAndFrameOptions;
    // currentStackLevel and currentThreadId are only necessary when
    // we must use -thread-select and -stack-select-frame
    private int fCurrentStackLevel  = -1;
    private String fCurrentThreadId = null;
    
    
    private final BlockingQueue<CommandHandle> fTxCommands = new LinkedBlockingQueue<CommandHandle>();
    private final Map<Integer, CommandHandle>  fRxCommands = Collections.synchronizedMap(new HashMap<Integer, CommandHandle>());

    /**
     * Handle that's inserted into the TX commands queue to signal 
     * that the TX thread should shut down.
     */
    private final CommandHandle fTerminatorHandle = new CommandHandle(null, null);

    /*
     *   Various listener control variables used to keep track of listeners who want to monitor
     *   what the control object is doing.
     */

    private final List<ICommandListener> fCommandProcessors = new ArrayList<ICommandListener>();
    private final List<IEventListener>   fEventProcessors = new ArrayList<IEventListener>();
    
    /**
     *   Current command which have not been handed off to the backend yet.
     */
    
    private final List<CommandHandle> fCommandQueue = new ArrayList<CommandHandle>();

    /**
     * Flag indicating that the command control has stopped processing commands.
     */
    private boolean fStoppedCommandProcessing = false;
    
    public AbstractMIControl(DsfSession session) {
        super(session);
        fUseThreadAndFrameOptions = false;
    }

    /**
     * @since 1.1
     */
    public AbstractMIControl(DsfSession session, boolean useThreadAndFrameOptions) {
        super(session);
        fUseThreadAndFrameOptions = useThreadAndFrameOptions;
    }

    /**
     * Starts the threads that process the debugger input/output channels.
     * To be invoked by the initialization routine of the extending class.
     * 
     * @param inStream
     * @param outStream
     */
    
    protected void startCommandProcessing(InputStream inStream, OutputStream outStream) {
    	
        fTxThread = new TxThread(outStream);
        fRxThread = new RxThread(inStream);
        fTxThread.start();
        fRxThread.start();
    }
    
    /**
     *  Stops the threads that process the debugger input/output channels, and notifies the
     *  results of the outstanding commands. To be invoked by the shutdown routine of the 
     *  extending class.
     * 
     * @param inStream
     * @param outStream
     */

    private Status genStatus(String str) {
    	return new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, INVALID_STATE, str, null);
    }
    
    protected void stopCommandProcessing() {
        // Guard against calling this multiple times (e.g. as a result of a 
        // user request and an event from the back end).
        if (fStoppedCommandProcessing) return;
        fStoppedCommandProcessing = true;
        
    	/*
    	 *  First go through the commands which have been queueud and not yet sent to the backend.
    	 */
    	for (CommandHandle commandHandle : fCommandQueue) {
            if (commandHandle.getRequestMonitor() == null) continue;
            commandHandle.getRequestMonitor().setStatus(genStatus("Connection is shut down")); //$NON-NLS-1$
            commandHandle.getRequestMonitor().done();
        }
    	fCommandQueue.clear();
    	
    	/*
    	 *  Now go through the commands which are outstanding in that they have been sent to the backend.
    	 */
        synchronized(fRxCommands) {
            for (CommandHandle commandHandle : fRxCommands.values()) {
                if (commandHandle.getRequestMonitor() == null) continue;
                commandHandle.getRequestMonitor().setStatus(genStatus( "Connection is shut down")); //$NON-NLS-1$
                commandHandle.getRequestMonitor().done();
            }
            fRxCommands.clear();
        }
        
        /*
         *  Now handle any requests which have not been transmitted, but weconsider them handed off.
         */
        List<CommandHandle> txCommands = new ArrayList<CommandHandle>();
        fTxCommands.drainTo(txCommands);
        for (CommandHandle commandHandle : txCommands) {
            if (commandHandle.getRequestMonitor() == null) continue;
            commandHandle.getRequestMonitor().setStatus(genStatus("Connection is shut down")); //$NON-NLS-1$
            commandHandle.getRequestMonitor().done();
        }
        
        // Queue a null value to tell the send thread to shut down.
        fTxCommands.add(fTerminatorHandle);
    }
    
    /**
     * Queues the given MI command to be sent to the debugger back end.  
     * 
     * @param command Command to be executed.  This parameter must be an 
     * instance of DsfMICommand, otherwise a ClassCastException will be 
     * thrown. 
     * @param rm Request completion monitor
     * 
     * @see org.eclipse.dd.dsf.debug.service.command.ICommandControl#addCommand(org.eclipse.dd.dsf.debug.service.command.ICommand, org.eclipse.dd.dsf.concurrent.RequestMonitor)
     */
    
    public <V extends ICommandResult> ICommandToken queueCommand(final ICommand<V> command, DataRequestMonitor<V> rm) {

        // Cast the command to MI Command type.  This will cause a cast exception to be 
        // thrown if the client did not give an MI command as an argument.
        @SuppressWarnings("unchecked")
              MICommand<MIInfo> miCommand = (MICommand<MIInfo>) command;

        // Cast the return token to match the result type of MI Command.  This is checking
        // against an erased type so it should never throw any exceptions.
        @SuppressWarnings("unchecked")
        DataRequestMonitor<MIInfo> miDone = (DataRequestMonitor<MIInfo>) rm;

        final CommandHandle handle = new CommandHandle(miCommand, miDone);

        // If the command control stopped processing commands, just return an error immediately. 
        if (fStoppedCommandProcessing) {
            rm.setStatus(genStatus("Connection is shut down")); //$NON-NLS-1$
            rm.done();
        } else {
        	/*
        	 *  We only allow three outstanding commands to be on the wire to the backend
        	 *  at any one time. This allows for coalescing as well as canceling
        	 *  existing commands on a state change. So we add it to the waiting list and let
        	 *  the user know they can now work with this item if need be.
        	 */
        	fCommandQueue.add(handle);
            processCommandQueued(handle);
            
            if (fRxCommands.size() < 3) {
                // In a separate dispatch cycle.  This allows command listeners 
            	// to respond to the command queued event.  
                getExecutor().execute(new DsfRunnable() {
                    public void run() {
                        processNextQueuedCommand();
                    }
                });
            }
        }
        
        return handle;
    }

    private void processNextQueuedCommand() {
		if (fCommandQueue.size() > 0) {
			final CommandHandle handle = fCommandQueue.remove(0);
			if (handle != null) {
				processCommandSent(handle);

				// Older debuggers didn't support the --thread/--frame options
				// Also, not all commands support those options (e.g., CLI commands)
				if (!fUseThreadAndFrameOptions || !handle.getCommand().supportsThreadAndFrameOptions()) {
					// Without the --thread/--frame, we need to send the proper 
					// -thread-select and -stack-frame-select before sending the command
					
					final IDMContext targetContext = handle.fCommand.getContext();
					final String targetThread = handle.getThreadId();
					final int targetFrame = handle.getStackFrameId();

					// The thread-select and frame-select make sense only if the thread is stopped.
					IRunControl runControl = getServicesTracker().getService(IRunControl.class);
					IMIExecutionDMContext execDmc = DMContexts.getAncestorOfType(targetContext, IMIExecutionDMContext.class);
					if (runControl != null && execDmc != null && runControl.isSuspended(execDmc)) {
						// Before the command is sent, Check the Thread Id and send it to 
						// the queue only if the id has been changed. Also, don't send a threadId of 0,
						// because that id is only used internally for single-threaded programs
						if (targetThread != null && !targetThread.equals("0") && !targetThread.equals(fCurrentThreadId)) { //$NON-NLS-1$
							fCurrentThreadId = targetThread;
							resetCurrentStackLevel();
							CommandHandle cmdHandle = new CommandHandle(new MIThreadSelect(targetContext, targetThread), null);
							cmdHandle.generateTokenId();
							fTxCommands.add(cmdHandle);
						}

						// Before the command is sent, Check the Stack level and send it to 
						// the queue only if the level has been changed. 
						if (targetFrame >= 0 && targetFrame != fCurrentStackLevel) {
							fCurrentStackLevel = targetFrame;
							CommandHandle cmdHandle = new CommandHandle(new MIStackSelectFrame(targetContext, targetFrame), null);
							cmdHandle.generateTokenId();
							fTxCommands.add(cmdHandle);
						}
					}
				}

		    	handle.generateTokenId();
		    	fTxCommands.add(handle);
			}
		}
    }

    /*
     *   This is the command which allows the user to retract a previously issued command. The
     *   state of the command  is that it is in the waiting queue  and has not yet been handed 
     *   to the backend yet.
     *   
     * (non-Javadoc)
     * @see org.eclipse.dd.mi.service.command.IDebuggerControl#removeCommand(org.eclipse.dd.mi.service.command.commands.ICommand)
     */
    public void removeCommand(ICommandToken token) {
    	
    	synchronized(fCommandQueue) {
    		
    		for ( CommandHandle handle : fCommandQueue ) {
    			if ( handle.equals(token)) {
    				fCommandQueue.remove(handle);
    				
    				final CommandHandle finalHandle = handle;
                    getExecutor().execute(new DsfRunnable() {
                        public void run() {
                        	processCommandRemoved(finalHandle);
                        }
                    });
    				break;
    			}
    		}
    	}
    }
   
    /*
     *  Allows a user ( typically a cache manager ) to sign up a listener to monitor command queue
     *  activity.
     *  
     * (non-Javadoc)
     * @see org.eclipse.dd.mi.service.command.IDebuggerControl#addCommandListener(org.eclipse.dd.mi.service.command.IDebuggerControl.ICommandListener)
     */
    public void addCommandListener(ICommandListener processor) { fCommandProcessors.add(processor); }
    
    /*
     *  Allows a user ( typically a cache manager ) to remove a monitoring listener.
     * (non-Javadoc)
     * @see org.eclipse.dd.mi.service.command.IDebuggerControl#removeCommandListener(org.eclipse.dd.mi.service.command.IDebuggerControl.ICommandListener)
     */
    public void removeCommandListener(ICommandListener processor) { fCommandProcessors.remove(processor); }
    
    /*
     *  Allows a user to sign up to a listener which handles out of band messages ( events ).
     *  
     * (non-Javadoc)
     * @see org.eclipse.dd.mi.service.command.IDebuggerControl#addEventListener(org.eclipse.dd.mi.service.command.IDebuggerControl.IEventListener)
     */
    public void addEventListener(IEventListener processor) { fEventProcessors.add(processor); }
    
    /*
     *  Allows a user to remove a event monitoring listener.
     *  
     * (non-Javadoc)
     * @see org.eclipse.dd.mi.service.command.IDebuggerControl#removeEventListener(org.eclipse.dd.mi.service.command.IDebuggerControl.IEventListener)
     */
    public void removeEventListener(IEventListener processor) { fEventProcessors.remove(processor); }
    
    abstract public MIControlDMContext getControlDMContext();
    
    /**
     * @since 1.1
     */
    public boolean isActive() {
        return !fStoppedCommandProcessing;
    }
    
    /*
     *  These are the service routines which perform the various callouts back to the listeners.
     */

    private void processCommandQueued(CommandHandle commandHandle) {
        for (ICommandListener processor : fCommandProcessors) {
            processor.commandQueued(commandHandle);
        }
    }
    private void processCommandRemoved(CommandHandle commandHandle) {
        for (ICommandListener processor : fCommandProcessors) {
            processor.commandRemoved(commandHandle);
        }
    }
    
    private void processCommandSent(CommandHandle commandHandle) {
        for (ICommandListener processor : fCommandProcessors) {
            processor.commandSent(commandHandle);
        }
    }

    private void processCommandDone(CommandHandle commandHandle, ICommandResult result) {
        /*
         *  Tell the listeners we have completed this one.
         */
        for (ICommandListener processor : fCommandProcessors) {
            processor.commandDone(commandHandle, result);
        }
    }
    
    private void processEvent(MIOutput output) {
        for (IEventListener processor : fEventProcessors) {
            processor.eventReceived(output);
        }
    }

	/*
	 * A global counter for all command, the token will be use to identify uniquely a command.
	 * Unless the value wraps around which is unlikely.
	 */
    private int fTokenIdCounter = 0 ;
    
	private int getNewTokenId() {
		int count = ++fTokenIdCounter;
		// If we ever wrap around.
		if (count <= 0) {
			count = fTokenIdCounter = 1;
		}
		return count;
	}
	
	/*
	 *  Support class which creates a convenient wrapper for holding all information about an
	 *  individual request.
	 */
    
    private class CommandHandle implements ICommandToken {

        private MICommand<MIInfo> fCommand;
        private DataRequestMonitor<MIInfo> fRequestMonitor;
        private int fTokenId ;
        
        CommandHandle(MICommand<MIInfo> c, DataRequestMonitor<MIInfo> d) {
            fCommand = c; 
            fRequestMonitor = d;
            fTokenId = -1; // Only initialize to a real value when needed
        }
        
        public MICommand<MIInfo> getCommand() { return fCommand; }
        public DataRequestMonitor<MIInfo> getRequestMonitor() { return fRequestMonitor; }
        // This method allows us to generate the token Id when we area actually going to use
        // it.  It is meant to help order the token ids based on when commands will actually
        // be sent
        public void generateTokenId() { fTokenId = getNewTokenId(); }
        public Integer getTokenId() { return fTokenId; }
        
        public int getStackFrameId() {
        	IFrameDMContext frameCtx = DMContexts.getAncestorOfType(fCommand.getContext(), IFrameDMContext.class);
        	if(frameCtx != null)
        		return frameCtx.getLevel();
        	return -1;
        } 

        public String getThreadId() {
        	IMIExecutionDMContext execCtx = DMContexts.getAncestorOfType(fCommand.getContext(), IMIExecutionDMContext.class);
        	if(execCtx != null)
        		return Integer.toString(execCtx.getThreadId());
        	return null;
        } 
        
        @Override
        public String toString() {
            return Integer.toString(fTokenId) + fCommand;
        }
    }

    /*
     *  This is the transmitter thread. When a command is given to this thread it has been
     *  considered to be sent, even if it has not actually been sent yet.  This assumption
     *  makes it easier from state management.  Whomever fill this pipeline handles all of
     *  the required state notification ( callbacks ). This thread simply physically gives
     *  the message to the backend. 
     */
    
    private class TxThread extends Thread {

        final private OutputStream fOutputStream; 
        
        public TxThread(OutputStream outStream) {
            super("MI TX Thread"); //$NON-NLS-1$
            fOutputStream = outStream;
        }

        @Override
        public void run () {
            while (true) {
                CommandHandle commandHandle = null;
                
                /*
                 *   Note: Acquiring locks for both fRxCommands and fTxCommands collections. 
                 */
                synchronized(fTxCommands) {
                    try {
                        commandHandle = fTxCommands.take();
                    } catch (InterruptedException e) {
                        break;  // Shutting down.
                    }
        
                    if (commandHandle == fTerminatorHandle) {
                        
                        break; // Null command is an indicator that we're shutting down. 
                    }
                    
                    /*
                     *  We note that this is an outstanding request at this point.
                     */
                    fRxCommands.put(commandHandle.getTokenId(), commandHandle);
                }
                
                /*
                 *   Construct the new command and push this command out the pipeline.
                 */

                final String str;
				// Not all commands support the --thread/--frame options (e.g., CLI commands)
                if (fUseThreadAndFrameOptions && commandHandle.getCommand().supportsThreadAndFrameOptions()) {
                	str = commandHandle.getTokenId() + commandHandle.getCommand().constructCommand(commandHandle.getThreadId(),
                			                                                                       commandHandle.getStackFrameId());
                } else {
                	str = commandHandle.getTokenId() + commandHandle.getCommand().constructCommand();
                }
                
                try {
                    if (fOutputStream != null) {
                        fOutputStream.write(str.getBytes());
                        fOutputStream.flush();

                        MIPlugin.debug(MIPlugin.getDebugTime() + " " + str); //$NON-NLS-1$
                    }
                } catch (IOException e) {
                    // Shutdown thread in case of IO error.
                    break;
                }
            }
        }
    }

    private class RxThread extends Thread {
        private final InputStream fInputStream;
        private final MIParser fMiParser = new MIParser();

        /** 
        * List of out of band records since the last result record.  Out of band records are 
        * required for processing the results of CLI commands.   
        */ 
        private final List<MIOOBRecord> fAccumulatedOOBRecords = new ArrayList<MIOOBRecord>();
        
        public RxThread(InputStream inputStream) {
            super("MI RX Thread"); //$NON-NLS-1$
            fInputStream = inputStream;
        }

        @Override
        public void run() {
            BufferedReader reader = new BufferedReader(new InputStreamReader(fInputStream));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() != 0) {
                        MIPlugin.debug(MIPlugin.getDebugTime() + " " + line +"\n"); //$NON-NLS-1$ //$NON-NLS-2$
                    	processMIOutput(line);
                    }
                }
            } catch (IOException e) {
                // Socket is shut down.
            } catch (RejectedExecutionException e) {
                // Dispatch thread is down.
            }
        }
        
        private MIResult findResultRecord(MIResult[] results, String variable) {
            for (int i = 0; i < results.length; i++) {
                if (variable.equals(results[i].getVariable())) {
                    return results[i];
                }
            }
            return null;
        }
        
        private String getStatusString(MICommand<MIInfo> origCommand, MIOutput response ) {
            
        	// Attempt to extract a message from the result record:
        	String message = null;
        	String[] parameters = null;
        	if (response != null && response.getMIResultRecord() != null) {
        		MIResult[] results = response.getMIResultRecord().getMIResults();

        		// Extract the parameters
        		MIResult paramsRes = findResultRecord(results, "parameters"); //$NON-NLS-1$
        		if (paramsRes != null && paramsRes.getMIValue() instanceof MIList) {
        			MIValue[] paramValues = ((MIList)paramsRes.getMIValue()).getMIValues();
        			parameters = new String[paramValues.length];
        			for (int i = 0; i < paramValues.length; i++) {
        				if (paramValues[i] instanceof MIConst) {
        					parameters[i] = ((MIConst)paramValues[i]).getString();
        				} else {
        					parameters[i] = ""; //$NON-NLS-1$
        				}
        			}
        		}
        		MIResult messageRes = findResultRecord(results, "message"); //$NON-NLS-1$
        		if (messageRes != null && messageRes.getMIValue() instanceof MIConst) {
        			message = ((MIConst)messageRes.getMIValue()).getString();
        		}
        		// FRCH: I believe that the actual string is "msg" ...
        		// FRCH: (at least for the version of gdb I'm using)
        		else {
        			messageRes = findResultRecord(results, "msg"); //$NON-NLS-1$
        			if (messageRes != null && messageRes.getMIValue() instanceof MIConst) {
        				message = ((MIConst)messageRes.getMIValue()).getString();
        			}
        		}
        	}
        	StringBuilder clientMsg = new StringBuilder();
        	clientMsg.append("Failed to execute MI command:\n"); //$NON-NLS-1$
        	clientMsg.append(origCommand.toString());
        	if (message != null) {
        		clientMsg.append("Error message from debugger back end:\n"); //$NON-NLS-1$
        		if (parameters != null) {
        			try {
        				clientMsg.append(MessageFormat.format(message, (Object[])parameters));
        			} catch(IllegalArgumentException e2) {
        				// Message format string invalid.  Fallback to just appending the strings. 
        				clientMsg.append(message);
        				clientMsg.append(parameters);
        			}
        		} else {
        			clientMsg.append(message);
        		}
        	}
        	return clientMsg.toString();
        }

        void processMIOutput(String line) {
            MIParser.RecordType recordType = fMiParser.getRecordType(line);
            
            if (recordType == MIParser.RecordType.ResultRecord) { 
                final MIResultRecord rr = fMiParser.parseMIResultRecord(line);
           
            	
            	/*
            	 *  Find the command in the current output list. If we cannot then this is
            	 *  some form of asynchronous notification. Or perhaps general IO.
            	 */
                int id = rr.getToken();
                final CommandHandle commandHandle = fRxCommands.remove(id);

                if (commandHandle != null) {
                    final MIOutput response = new MIOutput(
                        rr, fAccumulatedOOBRecords.toArray(new MIOOBRecord[fAccumulatedOOBRecords.size()]) );
                    fAccumulatedOOBRecords.clear();
                	
                	MIInfo result = commandHandle.getCommand().getResult(response);
					DataRequestMonitor<MIInfo> rm = commandHandle.getRequestMonitor();
					
					/*
					 *  Not all users want to get there results. They indicate so by not having
					 *  a completion object. 
					 */
					if ( rm != null ) {
						rm.setData(result);
						
						/*
						 * We need to indicate if this request had an error or not.
						 */
						String errorResult =  rr.getResultClass();
						
						if ( errorResult.equals(MIResultRecord.ERROR) ) {
							String status = getStatusString(commandHandle.getCommand(),response);
							rm.setStatus(new Status(IStatus.ERROR, MIPlugin.PLUGIN_ID, REQUEST_FAILED, status, null)); 
						}
						
						/*
						 *  We need to complete the command on the DSF thread for data security.
						 */
						final ICommandResult finalResult = result;
						getExecutor().execute(new DsfRunnable() {
	                        public void run() {
	                        	/*
	                        	 *  Complete the specific command.
	                        	 */
	                            if (commandHandle.getRequestMonitor() != null) {
	                                commandHandle.getRequestMonitor().done();
	                            }
	                            
	                            /*
	                             *  Now tell the generic listeners about it.
	                             */
	                            processCommandDone(commandHandle, finalResult);
	                        }
	                        @Override
                            public String toString() {
	                            return "MI command output received for: " + commandHandle.getCommand(); //$NON-NLS-1$
	                        }
	                    });
					} else {
						/*
						 *  While the specific requestor did not care about the completion  we
						 *  need to call any listeners. This could have been a CLI command for
						 *  example and  the CommandDone listeners there handle the IO as part
						 *  of the work.
						 */
						final ICommandResult finalResult = result;
						getExecutor().execute(new DsfRunnable() {
	                        public void run() {
	                            processCommandDone(commandHandle, finalResult);
	                        }
	                        @Override
                            public String toString() {
	                            return "MI command output received for: " + commandHandle.getCommand(); //$NON-NLS-1$
	                        }
	                    });
					}
                } else {
                    /*
                     *  GDB apparently can sometimes send multiple responses to the same command.  In those cases, 
                     *  the command handle is gone, so post the result as an event.  To avoid processing OOB records
                     *  as events multiple times, do not include the accumulated OOB record list in the response 
                     *  MIOutput object.  
                     */
                    final MIOutput response = new MIOutput(rr, new MIOOBRecord[0]);

                    getExecutor().execute(new DsfRunnable() {
                        public void run() {
                            processEvent(response);
                        }
                        @Override
                        public String toString() {
                            return "MI asynchronous output received: " + response; //$NON-NLS-1$
                        }
                    });
                }
        	} else if (recordType == MIParser.RecordType.OOBRecord) {
				// Process OOBs
        		final MIOOBRecord oob = fMiParser.parseMIOOBRecord(line);
       	        fAccumulatedOOBRecords.add(oob);
       	        final MIOutput response = new MIOutput(oob);


				
            	/*
            	 *   OOBS are events. So we pass them to any event listeners who want to see them. Again this must
            	 *   be done on the DSF thread for integrity.
            	 */
                getExecutor().execute(new DsfRunnable() {
                    public void run() {
                        processEvent(response);
                    }
                    @Override
                    public String toString() {
                        return "MI asynchronous output received: " + response; //$NON-NLS-1$
                    }
                });
            }
            
            getExecutor().execute(new DsfRunnable() {
            	public void run() {
        			processNextQueuedCommand();
            	}
            });
        }
    }

    // we keep track of currentStackLevel and currentThreadId because in
    // some cases we must use -thread-select and -stack-select-frame
    public void resetCurrentThreadLevel(){
    	fCurrentThreadId = null; 
    }
    
    public void resetCurrentStackLevel(){
    	fCurrentStackLevel = -1; 
    }

}
