/*******************************************************************************
 * Copyright (c) 2006, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *     Ericsson			  - Modified for additional functionality
 *******************************************************************************/
package org.eclipse.dd.dsf.debug.service;

import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.dsf.datamodel.IDMData;
import org.eclipse.dd.dsf.datamodel.IDMEvent;
import org.eclipse.dd.dsf.datamodel.IDMService;

/**
 * This interface provides access to controlling and monitoring the execution 
 * state of a process being debugged.  This interface does not actually 
 * provide methods for creating or destroying execution contexts, it doesn't
 * even have methods for getting labels.  That's because it is expected that
 * higher level services, ones that deal with processes, kernels, or target 
 * features will provide that functionality. 
 */
public interface IRunControl extends IDMService
{
    /**
     * Execution context is the object on which run control operations can be
     * performed.  A lot of higher-level services reference this context to build
     * functionality on top of it, e.g. stack, expression evaluation, registers, etc.
     */
    public interface IExecutionDMContext extends IDMContext {}
    
    /**
     * Context representing a process, kernel, or some other logical container 
     * for execution contexts, which by itself can perform run-control
     * operations. 
     */

    public interface IContainerDMContext extends IExecutionDMContext {}

    /** Flag indicating reason context state change. */
    public enum StateChangeReason { UNKNOWN, USER_REQUEST, STEP, BREAKPOINT, EXCEPTION, CONTAINER, WATCHPOINT, SIGNAL, SHAREDLIB, ERROR, EVALUATION };
        
    /**
     * Indicates that the given thread has suspended.
     */
    public interface ISuspendedDMEvent extends IDMEvent<IExecutionDMContext> {
        StateChangeReason getReason();
    }
    
    /**
     * Indicates that the given thread has resumed.
     */
    public interface IResumedDMEvent extends IDMEvent<IExecutionDMContext> {
        StateChangeReason getReason();
    }

    /**
     * Indicates that the given container has suspended.
     */
    public interface IContainerSuspendedDMEvent extends ISuspendedDMEvent {
        /**
         * Returns the contexts which triggered the resume, which could be 
         * an empty array if not known. 
         */
        IExecutionDMContext[] getTriggeringContexts();
    }

    /**
     * Indicates that the given container has resumed.
     */
    public interface IContainerResumedDMEvent extends IResumedDMEvent {
        /**
         * Returns the contexts which triggered the resume, which could be an 
         * empty array if not known.
         */
        IExecutionDMContext[] getTriggeringContexts();
    }
    
    /**
     * Indicates that a new execution context was started.  
     */
    public interface IStartedDMEvent extends IDMEvent<IExecutionDMContext> {}

    /**
     * Indicates that an execution context has exited. 
     */
    public interface IExitedDMEvent extends IDMEvent<IExecutionDMContext> {}

    /**
     * Display information for an execution context.
     */
    public interface IExecutionDMData extends IDMData {
        StateChangeReason getStateChangeReason();
    }

    /**
     * Retrieves execution data for given context.
     * @param dmc Context to retrieve data for.
     * @param rm Request completion monitor.
     */
    public void getExecutionData(IExecutionDMContext dmc, DataRequestMonitor<IExecutionDMData> rm);
    
    /**
     * Returns execution contexts belonging to the given container context.
     */
    public void getExecutionContexts(IContainerDMContext c, DataRequestMonitor<IExecutionDMContext[]> rm);

    /*
     * Run control commands.  They all require the IExecutionContext object on 
     * which they perform the operations.  
     */
    void canResume(IExecutionDMContext context, DataRequestMonitor<Boolean> rm);
    void canSuspend(IExecutionDMContext context, DataRequestMonitor<Boolean> rm);
    boolean isSuspended(IExecutionDMContext context);
    void resume(IExecutionDMContext context, RequestMonitor requestMonitor);
    void suspend(IExecutionDMContext context, RequestMonitor requestMonitor);
    public enum StepType { STEP_OVER, STEP_INTO, STEP_RETURN, INSTRUCTION_STEP_OVER, INSTRUCTION_STEP_INTO, INSTRUCTION_STEP_RETUTRN };
    boolean isStepping(IExecutionDMContext context);
    void canStep(IExecutionDMContext context, StepType stepType, DataRequestMonitor<Boolean> rm);
    void step(IExecutionDMContext context, StepType stepType, RequestMonitor requestMonitor);
}
