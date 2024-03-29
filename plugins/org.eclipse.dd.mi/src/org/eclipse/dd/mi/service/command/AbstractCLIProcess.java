/*******************************************************************************
 * Copyright (c) 2000, 2007 QNX Software Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     QNX Software Systems - Initial API and implementation
 *     Wind River Systems   - Modified for new DSF Reference Implementation
 *     Ericsson 		  	- Modified for additional features in DSF Reference implementation
 *******************************************************************************/

package org.eclipse.dd.mi.service.command;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.dd.dsf.concurrent.ConfinedToDsfExecutor;
import org.eclipse.dd.dsf.concurrent.DsfRunnable;
import org.eclipse.dd.dsf.concurrent.ThreadSafe;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.dsf.debug.service.command.ICommand;
import org.eclipse.dd.dsf.debug.service.command.ICommandControlService;
import org.eclipse.dd.dsf.debug.service.command.ICommandListener;
import org.eclipse.dd.dsf.debug.service.command.ICommandResult;
import org.eclipse.dd.dsf.debug.service.command.ICommandToken;
import org.eclipse.dd.dsf.debug.service.command.IEventListener;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.mi.internal.MIPlugin;
import org.eclipse.dd.mi.service.command.commands.CLICommand;
import org.eclipse.dd.mi.service.command.commands.MIInterpreterExecConsole;
import org.eclipse.dd.mi.service.command.commands.RawCommand;
import org.eclipse.dd.mi.service.command.output.MIConsoleStreamOutput;
import org.eclipse.dd.mi.service.command.output.MIInfo;
import org.eclipse.dd.mi.service.command.output.MILogStreamOutput;
import org.eclipse.dd.mi.service.command.output.MIOOBRecord;
import org.eclipse.dd.mi.service.command.output.MIOutput;

/**
 * This Process implementation tracks the process the GDB process.  This 
 * process object is displayed in Debug view and is used to
 * accept CLI commands and to write their output to the console.   
 * 
 * @see org.eclipse.debug.core.model.IProcess 
 */
@ThreadSafe
public abstract class AbstractCLIProcess extends Process 
    implements IEventListener, ICommandListener 
{
    public static final String PRIMARY_PROMPT = "(gdb)"; //$NON-NLS-1$
    public static final String SECONDARY_PROMPT = ">"; //$NON-NLS-1$

    private final DsfSession fSession;
    private final ICommandControlService fCommandControl;
	private final OutputStream fOutputStream = new CLIOutputStream();
    
    // Client process console stream.
    private final PipedInputStream fMIInConsolePipe;
    private final PipedOutputStream fMIOutConsolePipe;
    private final PipedInputStream fMIInLogPipe;
    private final PipedOutputStream fMIOutLogPipe;

    private boolean fDisposed = false;
    
    /**
     * Counter for tracking console commands sent by services.  
     * 
     * Services may issue console commands when the available MI commands are
     * not sufficient.  However, these commands may produce console and log 
     * output which should not be written to the user CLI terminal.
     *   
     * This counter is incremented any time a console command is seen which was
     * not generated by this class.  It is decremented whenever a service CLI 
     * command is finished.  When counter value is 0, the CLI process writes 
     * the console output. 
     */
    private int fSuppressConsoleOutputCounter = 0;
         
    private int fPrompt = 1; // 1 --> Primary prompt "(gdb)"; 2 --> Secondary Prompt ">"

    /**
     * @since 1.1
     */
    @ConfinedToDsfExecutor("fSession#getExecutor")
	public AbstractCLIProcess(ICommandControlService commandControl) throws IOException {
        fSession = commandControl.getSession();
        fCommandControl = commandControl;
        
        commandControl.addEventListener(this);
        commandControl.addCommandListener(this);

        PipedInputStream miInConsolePipe = null;
        PipedOutputStream miOutConsolePipe = null;
        PipedInputStream miInLogPipe = null;
        PipedOutputStream miOutLogPipe = null;
        
        try {
        	// Using a LargePipedInputStream see https://bugs.eclipse.org/bugs/show_bug.cgi?id=223154
            miOutConsolePipe = new PipedOutputStream();
            miInConsolePipe = new LargePipedInputStream(miOutConsolePipe);
            miOutLogPipe = new PipedOutputStream();
            miInLogPipe = new LargePipedInputStream(miOutLogPipe);
        } catch (IOException e) {
            ILog log = MIPlugin.getDefault().getLog();
            if (log != null) {
                log.log(new Status(
                    IStatus.ERROR, MIPlugin.PLUGIN_ID, -1, "Error when creating log pipes", e)); //$NON-NLS-1$
            }                   
        }
        // Must initialize these outside of the try block because they are final.
        fMIOutConsolePipe = miOutConsolePipe;
        fMIInConsolePipe = miInConsolePipe;
        fMIOutLogPipe = miOutLogPipe;
        fMIInLogPipe = miInLogPipe; 
	}
    
    @Deprecated
    public AbstractCLIProcess(AbstractMIControl commandControl) throws IOException {
        this ( (ICommandControlService)commandControl );
    }
    
    protected DsfSession getSession() { return fSession; }

    @Deprecated
	protected AbstractMIControl getCommandControl() { return (AbstractMIControl)fCommandControl; }
    
    /**
     * @since 1.1
     */
	protected ICommandControlService getCommandControlService() { return fCommandControl; }
	
	protected boolean isDisposed() { return fDisposed; }
	
    @ConfinedToDsfExecutor("fSession#getExecutor")
    public void dispose() {
        fCommandControl.removeEventListener(this);
        fCommandControl.removeCommandListener(this);
        
        closeIO();
        fDisposed = true;
    }
    
    private void closeIO() {
        try {
            fMIOutConsolePipe.close();
        } catch (IOException e) {}
        try {
            fMIInConsolePipe.close();
        } catch (IOException e) {}
        try {
            fMIOutLogPipe.close();
        } catch (IOException e) {}
        try {
            fMIInLogPipe.close();
        } catch (IOException e) {}
        
    }
    
	/**
	 * @see java.lang.Process#getErrorStream()
	 */
	@Override
    public InputStream getErrorStream() {
        return fMIInLogPipe;
	}

	/**
	 * @see java.lang.Process#getInputStream()
	 */
	@Override
    public InputStream getInputStream() {
        return fMIInConsolePipe;
	}

	/**
	 * @see java.lang.Process#getOutputStream()
	 */
	@Override
    public OutputStream getOutputStream() {
		return fOutputStream;
	}


    public void eventReceived(Object output) {
    	if (fSuppressConsoleOutputCounter > 0) return;
    	for (MIOOBRecord oobr : ((MIOutput)output).getMIOOBRecords()) {
    		if (oobr instanceof MIConsoleStreamOutput)  
    		{
                MIConsoleStreamOutput out = (MIConsoleStreamOutput) oobr;
                String str = out.getString();
                // Process the console stream too.
                setPrompt(str);
                try {
                    fMIOutConsolePipe.write(str.getBytes());
                    fMIOutConsolePipe.flush();
                } catch (IOException e) {
                }
            } else if (oobr instanceof MILogStreamOutput) {
            	MILogStreamOutput out = (MILogStreamOutput) oobr;
                String str = out.getString();
                if (str != null) {
                    try {
	                        fMIOutLogPipe.write(str.getBytes());
	                        fMIOutLogPipe.flush();
                    } catch (IOException e) {
                    }
                }
            }
        }
    }
    
    public void commandQueued(ICommandToken token) {
            // Ignore
    }

    public void commandSent(ICommandToken token) {
        ICommand<?> command = token.getCommand();
        // Check if the command is a CLI command and if it did not originate from this class.
        if (command instanceof CLICommand<?> &&
            !(command instanceof ProcessCLICommand || command instanceof ProcessMIInterpreterExecConsole)) 
        {
            fSuppressConsoleOutputCounter++;
        }
    }

    public void commandRemoved(ICommandToken token) {
            // Ignore
    }

    public void commandDone(ICommandToken token, ICommandResult result) {
        ICommand<?> command = token.getCommand();
    	if (token.getCommand() instanceof CLICommand<?> &&
    			!(command instanceof ProcessCLICommand || command instanceof ProcessMIInterpreterExecConsole)) 
        {
            fSuppressConsoleOutputCounter--;
        }
     }

    void setPrompt(String line) {
        fPrompt = 0;
        // See https://bugs.eclipse.org/bugs/show_bug.cgi?id=109733
        if (line == null)
            return;
        line = line.trim();
        if (line.equals(PRIMARY_PROMPT)) {
            fPrompt = 1;
        } else if (line.equals(SECONDARY_PROMPT)) {
            fPrompt = 2;
        }
    }

    public boolean inPrimaryPrompt() {
        return fPrompt == 1;
    }

    public boolean inSecondaryPrompt() {
        return fPrompt == 2;
    }
    
    private boolean isMIOperation(String operation) {
    	// The definition of an MI command states that it starts with
    	//  [ token ] "-"
    	// where 'token' is optional and a sequence of digits.
    	// However, we don't accept a token from the user, because
    	// we will be adding our own token when actually sending the command.
    	if (operation.startsWith("-")) { //$NON-NLS-1$
    		return true;
    	}
    	return false;
    }

    private class CLIOutputStream extends OutputStream {
        private final StringBuffer buf = new StringBuffer();
        
        @Override
        public void write(int b) throws IOException {
            buf.append((char)b);
            if (b == '\n') {
                // Throw away the newline.
                final String bufString = buf.toString().trim();
                buf.setLength(0);
                try {
                    fSession.getExecutor().execute(new DsfRunnable() { public void run() {
                        try {
                            post(bufString);
                        } catch (IOException e) {
                            // Pipe closed.
                        }
                    }});
                } catch (RejectedExecutionException e) {
                    // Session disposed.
                }
            }
        }
                        
        // Encapsulate the string sent to gdb in a fake
        // command and post it to the TxThread.
        public void post(String str) throws IOException {
            if (isDisposed()) return;
            ICommand<MIInfo> cmd = null;
            // 1-
            // if We have the secondary prompt it means
            // that GDB is waiting for more feedback, use a RawCommand
            // 2-
            // Do not use the interpreter-exec for stepping operation
            // the UI will fall out of step.  
            // Also, do not use "interpreter-exec console" for MI commands.
            // 3-
            // Normal Command Line Interface.
            boolean secondary = inSecondaryPrompt();
            if (secondary) {
                cmd = new RawCommand(getCommandControlService().getContext(), str);
            }
            else if (! isMIOperation(str) &&
            		 ! CLIEventProcessor.isSteppingOperation(str))
            {
                cmd = new ProcessMIInterpreterExecConsole(getCommandControlService().getContext(), str);
            } 
            else {
                cmd = new ProcessCLICommand(getCommandControlService().getContext(), str);
            }
            final ICommand<MIInfo> finalCmd = cmd; 
            fSession.getExecutor().execute(new DsfRunnable() { public void run() {
                if (isDisposed()) return;
                // Do not wait around for the answer.
                getCommandControlService().queueCommand(finalCmd, null);
            }});
        }
    }
    
    private class ProcessCLICommand extends CLICommand<MIInfo> {
        public ProcessCLICommand(IDMContext ctx, String oper) {
            super(ctx, oper);
        }
    }
    
    private class ProcessMIInterpreterExecConsole extends MIInterpreterExecConsole<MIInfo> {
        public ProcessMIInterpreterExecConsole(IDMContext ctx, String cmd) {
            super(ctx, cmd);
        }
    }
}
