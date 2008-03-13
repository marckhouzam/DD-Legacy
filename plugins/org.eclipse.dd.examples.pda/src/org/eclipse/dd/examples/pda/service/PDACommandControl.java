package org.eclipse.dd.examples.pda.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.DsfRunnable;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.concurrent.ThreadSafe;
import org.eclipse.dd.dsf.debug.service.command.ICommand;
import org.eclipse.dd.dsf.debug.service.command.ICommandControl;
import org.eclipse.dd.dsf.debug.service.command.ICommandListener;
import org.eclipse.dd.dsf.debug.service.command.ICommandResult;
import org.eclipse.dd.dsf.debug.service.command.IEventListener;
import org.eclipse.dd.dsf.service.AbstractDsfService;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.examples.pda.PDAPlugin;
import org.eclipse.dd.examples.pda.service.commands.AbstractPDACommand;
import org.eclipse.dd.examples.pda.service.commands.PDACommandResult;
import org.eclipse.dd.examples.pda.service.commands.PDAExitCommand;
import org.osgi.framework.BundleContext;


/**
 * Service that handles communication with a PDA debugger back end.  
 */
public class PDACommandControl extends AbstractDsfService implements ICommandControl {

    // Structure used to store command information in services internal queues.
    private static class CommandHandle {
        final private AbstractPDACommand<PDACommandResult> fCommand;
        final private DataRequestMonitor<PDACommandResult> fRequestMonitor;
        
        CommandHandle(AbstractPDACommand<PDACommandResult> c, DataRequestMonitor<PDACommandResult> rm) {
            fCommand = c; 
            fRequestMonitor = rm;
        }
    }

    // Parameters that the command control is created with.
    final private String fProgram;
    final private int fRequestPort;
    final private int fEventPort;

    // Queue of commands waiting to be sent to the debugger.  As long as commands
    // are in this queue, they can still be removed by clients. 
    private final List<CommandHandle> fCommandQueue = new LinkedList<CommandHandle>();
    
    // Queue of commands that are being sent to the debugger.  This queue is read
    // by the send job, so as soon as commands are inserted into this queue, they can
    // be considered as sent.
    @ThreadSafe
    private final BlockingQueue<CommandHandle> fTxCommands = new LinkedBlockingQueue<CommandHandle>();
    
    // Flag indicating that the PDA debugger started
    private boolean fStarted = false;
    
    // Flag indicating that the PDA debugger has been disconnected 
    @ThreadSafe
    private boolean fTerminated = false;
    
    //  Data Model context of this command control. 
    private PDAProgramDMContext fDMContext;

    // Synchronous listeners for commands and events.
    private final List<ICommandListener> fCommandListeners = new ArrayList<ICommandListener>();
    private final List<IEventListener>   fEventListeners = new ArrayList<IEventListener>();
    
    // Sockets for communicating with PDA debugger 
    @ThreadSafe
    private Socket fRequestSocket;
    @ThreadSafe
    private PrintWriter fRequestWriter;
    @ThreadSafe
    private BufferedReader fRequestReader;
    @ThreadSafe
    private Socket fEventSocket;
    @ThreadSafe
    private BufferedReader fEventReader;

    // Jobs servicing the sockets.
    private EventDispatchJob fEventDispatchJob;
    private CommandSendJob fCommandSendJob;
    
    /**
     * Command control constructor. 
     * @param session The DSF session that this service is a part of. 
     * @param requestPort Port number for sending PDA commands.
     * @param eventPort Port for listening to PDA events.
     */
    public PDACommandControl(DsfSession session, String program, int requestPort, int eventPort) {
        super(session);
        fProgram = program;
        fRequestPort = requestPort;
        fEventPort = eventPort;
    }
    
    @Override
    public void initialize(final RequestMonitor rm) {
        // Call the super-class to perform initialization first.
        super.initialize( new RequestMonitor(getExecutor(), rm) {
            @Override
            protected void handleOK() {
                doInitialize(rm);
            }
        });
    }

    private void doInitialize(final RequestMonitor rm) {
        // Create the control's data model context.
        fDMContext = new PDAProgramDMContext(getSession().getId(), fProgram);

        // Add a listener for PDA events to track the started/terminated state.
        addEventListener(new IEventListener() {
            public void eventReceived(Object output) {
                if ("started".equals(output)) {
                    setStarted();
                } else if ("terminated".equals(output)) {
                    setTerminated();
                }
            }
        });
        
        // Request monitor that will be invoked when the socket initialization is
        // completed.  
        final RequestMonitor socketsInitializeRm = new RequestMonitor(getExecutor(), rm) {
            @Override
            protected void handleOK() {
                // Register the service with OSGi as the last step in initialization of 
                // the service.
                register(
                    new String[]{ ICommandControl.class.getName(), PDACommandControl.class.getName() }, 
                    new Hashtable<String,String>());
                rm.done();
            }
        };
        
        // To avoid blocking the DSF dispatch thread use a job to initialize communication sockets.  
        new Job("PDA Initialize") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    // give interpreter a chance to start
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                    }
                    fRequestSocket = new Socket("localhost", fRequestPort);
                    fRequestWriter = new PrintWriter(fRequestSocket.getOutputStream());
                    fRequestReader = new BufferedReader(new InputStreamReader(fRequestSocket.getInputStream()));
                    // give interpreter a chance to open next socket
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                    }
                    fEventSocket = new Socket("localhost", fEventPort);
                    fEventReader = new BufferedReader(new InputStreamReader(fEventSocket.getInputStream()));

                    fEventDispatchJob = new EventDispatchJob();
                    fEventDispatchJob.schedule();
                    
                    fCommandSendJob = new CommandSendJob();
                    fCommandSendJob.schedule();

                    socketsInitializeRm.done();
                } catch (UnknownHostException e) {
                    socketsInitializeRm.setStatus(new Status(
                        IStatus.ERROR, PDAPlugin.PLUGIN_ID, REQUEST_FAILED, "Unable to connect to PDA VM", e));
                    socketsInitializeRm.done();
                } catch (IOException e) {
                    socketsInitializeRm.setStatus(new Status(
                        IStatus.ERROR, PDAPlugin.PLUGIN_ID, REQUEST_FAILED, "Unable to connect to PDA VM", e));
                    socketsInitializeRm.done();
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    @Override
    public void shutdown(final RequestMonitor requestMonitor) {
        // Unregister the service first, so that clients may no longer gain access to it.
        unregister();
        
        if (!isTerminated()) {
            // If the debugger is still connected, send it the exit command.
            terminate(new RequestMonitor(getExecutor(), requestMonitor) {
                @Override
                protected void handleCompleted() {
                    // Mark the command control as terminated.
                    setTerminated();

                    // Ignore any error resulting from the exit command.  
                    // Errors will most likely result if the PDA process is 
                    // already terminated.
                    requestMonitor.done();
                }
            });
        } else {
            requestMonitor.done();
        }
    }        

    @Override
    protected BundleContext getBundleContext() {
        return PDAPlugin.getBundleContext();
    }
    
    /**
     *  Job that services the send command queue. 
     */
    private class CommandSendJob extends Job {
        CommandSendJob() {
            super("PDA Command Send");
            setSystem(true);
        }
        
        @Override
        protected IStatus run(IProgressMonitor monitor) {
            while (!isTerminated()) {
                synchronized(fTxCommands) {
                    try {
                        // Remove comamnd from send queue.
                        final CommandHandle commandHandle = fTxCommands.take();
                        
                        // Send the request to PDA
                        fRequestWriter.println(commandHandle.fCommand.getRequest());
                        fRequestWriter.flush();
                        
                        try {
                            // wait for reply
                            final String response = fRequestReader.readLine();
                            
                            // Process the reply in the executor thread.
                            try {
                                getExecutor().execute(new DsfRunnable() {
                                    public void run() {
                                        processCommandDone(commandHandle, response);
                                    }
                                });
                            } catch (RejectedExecutionException e) {
                                // Acceptable race condition may see the session shut down
                                // while we're waiting for command response.  Still complete
                                // the request monitor.
                                assert isTerminated();
                                assert isTerminated();
                                PDAPlugin.failRequest(commandHandle.fRequestMonitor, REQUEST_FAILED, "Command control shut down.");
                            }
                        } catch (final IOException e) {
                            // Process error it in the executor thread
                            try {
                                getExecutor().execute(new DsfRunnable() {
                                    public void run() {
                                        processCommandException(commandHandle, e);
                                    }
                                });
                            } catch (RejectedExecutionException re) {
                                // Acceptable race condition... see above
                                assert isTerminated();
                                PDAPlugin.failRequest(commandHandle.fRequestMonitor, REQUEST_FAILED, "Command control shut down.");
                            }
                        }
                    } catch (InterruptedException e) {
                        break;  // Shutting down.
                    }
                }
            }
            return Status.OK_STATUS;
        }        
        
    }
    
    /**
     * Job that services the PDA event socket.
     */
    class EventDispatchJob extends Job {
        
        public EventDispatchJob() {
            super("PDA Event Listner");
            setSystem(true);
        }

        @Override
        protected IStatus run(IProgressMonitor monitor) {
            while (!isTerminated()) {
                try {
                    // Wait for an event.
                    final String event = fEventReader.readLine();
                    if (event != null) {
                        try {
                            // Process the event in executor thread.
                            getExecutor().execute(new DsfRunnable() {
                                public void run() {
                                    processEventReceived(event);
                                }
                            });
                        } catch (RejectedExecutionException e) {}                
                    } else {
                        break;
                    }
                } catch (IOException e) {
                    break;
                }
            }
            if (!isTerminated()) {
                // Exception from the event socket is an indicator that the PDA debugger
                // has exited.  Call setTerminated() in executor thread.
                try {
                    getExecutor().execute(new DsfRunnable() {
                        public void run() {
                            setTerminated();
                        }
                    });
                } catch (RejectedExecutionException e) {}                
            }
            return Status.OK_STATUS;
        }
        
    }
    
    public <V extends ICommandResult> void queueCommand(ICommand<V> command, DataRequestMonitor<V> rm) {
        if (command instanceof AbstractPDACommand<?>) {
            // Cast from command with "<V extends ICommandResult>" to a more concrete
            // type to use internally in the command control.
            @SuppressWarnings("unchecked")
            AbstractPDACommand<PDACommandResult> pdaCommand = (AbstractPDACommand<PDACommandResult>)command;
            
            // Similarly, cast the request monitor to a more concrete type.
            @SuppressWarnings("unchecked")
            DataRequestMonitor<PDACommandResult> pdaRM = (DataRequestMonitor<PDACommandResult>)rm;

            // Add the command to the queue and notify command listeners.
            fCommandQueue.add( new CommandHandle(pdaCommand, pdaRM) );
            for (ICommandListener listener : fCommandListeners) {
                listener.commandQueued(command);
            }
            
            // In a separate dispatch cycle.  This allows command listeners to repond to the 
            // command queued event.  
            getExecutor().execute(new DsfRunnable() {
                public void run() {
                    processQueues();
                }
            });
            
        } else {
            PDAPlugin.failRequest(rm, INTERNAL_ERROR, "Unrecognized command: " + command);
        }
    }

    public void cancelCommand(ICommand<? extends ICommandResult> command) {
        // This debugger is unable of canceling commands once they have 
        // been sent.
    }

    public void removeCommand(ICommand<? extends ICommandResult> command) {
        // Removes given command from the queue and notify the listeners
        for (Iterator<CommandHandle> itr = fCommandQueue.iterator(); itr.hasNext();) {
            CommandHandle handle = itr.next();
            if (command.equals(handle.fCommand)) {
                itr.remove();
                for (ICommandListener listener : fCommandListeners) {
                    listener.commandRemoved(command);
                }                
            }
        }
    }
    
    public void addCommandListener(ICommandListener processor) { 
        fCommandListeners.add(processor); 
    }
    
    public void removeCommandListener(ICommandListener processor) { 
        fCommandListeners.remove(processor); 
    }
    
    public void addEventListener(IEventListener processor) { 
        fEventListeners.add(processor); 
    }
    
    public void removeEventListener(IEventListener processor) { 
        fEventListeners.remove(processor); 
    }

    private void processCommandDone(CommandHandle handle, String response) {
        // Trace to debug output.
        PDAPlugin.debug("R: " + response);
        
        // Given the PDA response string, create the result using the command
        // that was sent.
        PDACommandResult result = handle.fCommand.createResult(response);
        
        // Set the result to the request monitor and return to sender.
        // Note: as long as PDA sends some response, a PDA command will never
        // return an error.
        handle.fRequestMonitor.setData(result);
        handle.fRequestMonitor.done();

        // Notify listeners of the response
        for (ICommandListener listener : fCommandListeners) {
            listener.commandDone(handle.fCommand, result);
        }
        
        // Process next command in queue.
        processQueues();
    }

    
    private void processCommandException(CommandHandle handle, Throwable exception) {
        
        // If sending a command resulted in an exception, notify the client.
        handle.fRequestMonitor.setStatus(new Status(
            IStatus.ERROR, PDAPlugin.PLUGIN_ID, REQUEST_FAILED, "Exception reading request response", exception));
        handle.fRequestMonitor.done();                            

        // Notify listeners also.
        for (ICommandListener listener : fCommandListeners) {
            listener.commandDone(handle.fCommand, null);
        }
    }

    private void processEventReceived(String event) {
        // Notify the listeners only.
        PDAPlugin.debug("E: " + event);
        for (IEventListener listener : fEventListeners) {
            listener.eventReceived(event);
        }
    }
    
    private synchronized void processQueues() {
        if (isTerminated()) {
            // If the PDA debugger is terminated.  Return all submitted commands 
            // with an error.
            for (CommandHandle handle : fCommandQueue) {
                handle.fRequestMonitor.setStatus(new Status(
                    IStatus.ERROR, PDAPlugin.PLUGIN_ID, INVALID_STATE, "Command control is terminated", null));
                handle.fRequestMonitor.done();
            }
            fCommandQueue.clear();
        } else if (fStarted && fTxCommands.isEmpty() && !fCommandQueue.isEmpty()) {
            // Process the queues if:
            // - the PDA debugger has started,
            // - there are no pending commands in the send queue,
            // - and there are commands waiting to be sent.
            CommandHandle handle = fCommandQueue.remove(0); 
            fTxCommands.add(handle);
            PDAPlugin.debug("C: " + handle.fCommand.getRequest());
            for (ICommandListener listener : fCommandListeners) {
                listener.commandSent(handle.fCommand);
            }            
        }
    }
    
    /**
     * Return the PDA Debugger top-level Data Model context. 
     * @see PDAProgramDMContext
     */
    @ThreadSafe
    public PDAProgramDMContext getProgramDMContext() {
        return fDMContext;
    }

    private void setStarted() {
        // Mark the command control as started and ready to process commands.
        fStarted = true;
        
        // Process any commands which may have been queued before the 
        processQueues();

        // Issue a data model event.
        getSession().dispatchEvent(new PDAStartedEvent(getProgramDMContext()), getProperties());
    }
    
    /**
     * Returns whether the PDA debugger has started and is processing commands.
     */
    public boolean isStarted() {
        return fStarted;
    }
    
    @ThreadSafe
    private synchronized void setTerminated() {
        // Set terminated may be called more than once: by event listener thread, 
        // by the terminate command, etc, so protect against sending events multiple
        // times.
        if (!fTerminated) {
            fTerminated = true;
            
            // Process any waiting commands, they all should return with an error.
            processQueues();
            
            // Issue a data model event.
            getSession().dispatchEvent(new PDATerminatedEvent(getProgramDMContext()), getProperties());
        }
    }

    /**
     * Returns whether the PDA debugger has been terminated.
     */
    @ThreadSafe
    public synchronized boolean isTerminated() {
        return fTerminated;
    }

    /**
     * Sends a command to PDA debugger to terminate.
     */
    public void terminate(RequestMonitor rm) {
        if (!isTerminated()) {
            queueCommand(
                new PDAExitCommand(fDMContext),
                new DataRequestMonitor<PDACommandResult>(getExecutor(), rm));
        } else {
            // If already terminated, indicate success.
            rm.done();
        }
    }    
}
