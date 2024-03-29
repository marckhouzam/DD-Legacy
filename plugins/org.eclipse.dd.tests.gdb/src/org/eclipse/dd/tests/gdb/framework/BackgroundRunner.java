/*******************************************************************************
 * Copyright (c) 2007 Ericsson and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Ericsson			  - Initial Implementation
 *******************************************************************************/
package org.eclipse.dd.tests.gdb.framework;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.dd.tests.gdb.launching.TestsPlugin;
import org.eclipse.swt.widgets.Display;
import org.junit.internal.runners.InitializationError;
import org.junit.internal.runners.TestClassRunner;
import org.junit.runner.notification.RunNotifier;

/**
 * This runner starts an eclipse job ro run the tests, so as
 * to release the UI thread.
 */
@SuppressWarnings("restriction")
public class BackgroundRunner extends TestClassRunner {

	public BackgroundRunner(Class<?> klass) throws InitializationError {
		super(klass);
	}

	final static QualifiedName BACKGROUND_TEST_EXECUTION_FINISHED = new QualifiedName(TestsPlugin.getDefault().getBundle().getSymbolicName(), "background_test_execution_finished"); //$NON-NLS-1$
	
	void invokeSuperRunImpl(RunNotifier notifier) {
		super.run(notifier);
	}

	/*
	 * This method overrides the one from TestClassRunner.  
	 * What we do here is start a background job which will call 
	 * TestClassRunner.run; this enables us to release
	 * the main UI thread.
	 * 
	 * This has been adapted from the JUnits tests of TargetManagement
	 * (RSECoreTestCase and RSEWaitAndDispatchUtil)
	 */
	@Override
	public void run(final RunNotifier notifier) {
		
		// Start the test in a background thread
		Job job = new Job("GDB/MI JUnit Test Case Execution Job") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				invokeSuperRunImpl(notifier);
				monitor.done();
				setProperty(BACKGROUND_TEST_EXECUTION_FINISHED, Boolean.TRUE);
				
				// The job never fails. The test result is the real result.
				return Status.OK_STATUS;
			}
		};

		// The job is not complete yet
	    job.setProperty(BACKGROUND_TEST_EXECUTION_FINISHED, Boolean.FALSE);
		// schedule the job to run immediatelly
		job.schedule();
		
		// wait till the job finishes executing
		waitAndDispatch(0, new BackgroundTestExecutionJobWaiter(job));
	}
	
	public interface IInterruptCondition {
		public boolean isTrue();
		public void dispose();
	}

	private final static class BackgroundTestExecutionJobWaiter implements IInterruptCondition {
		private final Job job;
		
		public BackgroundTestExecutionJobWaiter(Job job) {
			assert job != null;
			this.job = job;
		}

		public boolean isTrue() {
			// Interrupt the wait method if the job signaled that it has finished.
			return ((Boolean)job.getProperty(BACKGROUND_TEST_EXECUTION_FINISHED)).booleanValue();
		}
		
		public void dispose() { 
			// nothing to do
		}
	}

	public static boolean waitAndDispatch(long timeout, IInterruptCondition condition) {
		assert timeout >= 0 && condition != null;
		
		boolean isTimedOut= false;
		if (timeout >= 0 && condition != null) {
			long start = System.currentTimeMillis();
			Display display = Display.findDisplay(Thread.currentThread());
			if (display != null) {
				// ok, we are running within a display thread --> keep the
				// display event dispatching running.
				long current = System.currentTimeMillis();
				while (timeout == 0 || (current - start) < timeout) {
					if (condition.isTrue()) break;
					if (!display.readAndDispatch()) display.sleep();
					current = System.currentTimeMillis();
				}
				isTimedOut = (current - start) >= timeout && timeout > 0;
			} else {
				// ok, we are not running within a display thread --> we can
				// just block the thread here
				long current = System.currentTimeMillis();
				while (timeout == 0 || (current - start) < timeout) {
					if (condition.isTrue()) break;
					try { Thread.sleep(50); } catch (InterruptedException e) { /* ignored on purpose */ }
					current = System.currentTimeMillis();
				}
				isTimedOut = (current - start) >= timeout && timeout > 0;
			}
		}
		
		// Signal the interrupt condition that we are done here
		// and it can cleanup whatever necessary.
		condition.dispose();
		
		return isTimedOut;
	}

}
