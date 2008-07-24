/*******************************************************************************
 * Copyright (c) 2006, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.DsfExecutor;
import org.eclipse.dd.dsf.concurrent.DsfRunnable;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.datamodel.AbstractDMEvent;
import org.eclipse.dd.dsf.datamodel.DMContexts;
import org.eclipse.dd.dsf.debug.internal.ui.DsfDebugUIPlugin;
import org.eclipse.dd.dsf.debug.service.IRunControl;
import org.eclipse.dd.dsf.debug.service.IStepQueueManager;
import org.eclipse.dd.dsf.debug.service.IRunControl.IExecutionDMContext;
import org.eclipse.dd.dsf.debug.service.IRunControl.IResumedDMEvent;
import org.eclipse.dd.dsf.debug.service.IRunControl.ISuspendedDMEvent;
import org.eclipse.dd.dsf.debug.service.IRunControl.StateChangeReason;
import org.eclipse.dd.dsf.debug.service.IRunControl.StepType;
import org.eclipse.dd.dsf.debug.ui.IDsfDebugUIConstants;
import org.eclipse.dd.dsf.service.DsfServiceEventHandler;
import org.eclipse.dd.dsf.service.DsfServicesTracker;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;

/**
 * This class builds on top of standard run control service to provide
 * functionality for step queuing and delaying. Step queuing essentially allows
 * user to press and hold the step key and achieve maximum stepping speed. If
 * this class is used, other service implementations, such as stack and
 * expressions, can use it to avoid requesting data from debugger back end if
 * another step is about to be executed.
 * 
 * @since 1.1
 */
public final class SteppingController implements IStepQueueManager
{
    /**
     * Amount of time in milliseconds, that it takes the SteppingTimedOutEvent 
     * event to be issued after a step is started. 
     * @see SteppingTimedOutEvent  
     */
    public final static int STEPPING_TIMEOUT = 500;
    
    /**
     * The depth of the step queue.  In other words, the maximum number of steps 
     * that are queued before the step queue manager is throwing them away. 
     */
    public final static int STEP_QUEUE_DEPTH = 1;

	/**
	 * The maximum delay (in milliseconds) between steps when synchronized
	 * stepping is enabled. This also serves as a safeguard in the case stepping
	 * control participants fail to indicate completion of event processing.
	 */
    public final static int MAX_STEP_DELAY= 1000;

    /**
     * Indicates that the given context has been stepping for some time, 
     * and the UI (views and actions) may need to be updated accordingly. 
     */
	public static final class SteppingTimedOutEvent extends AbstractDMEvent<IExecutionDMContext> {
		private SteppingTimedOutEvent(IExecutionDMContext execCtx) {
			super(execCtx);
		}
	}

	/**
	 * Interface for clients interested in stepping control. When a stepping
	 * control participant is registered with the stepping controller, it is
	 * expected to call
	 * {@link SteppingController#doneStepping(IExecutionDMContext, ISteppingControlParticipant)
	 * doneStepping} as soon as a "step", i.e. a suspended event has been
	 * processed. If synchronized stepping is enabled, further stepping is
	 * blocked until all stepping control participants have indicated completion
	 * of event processing or the maximum timeout
	 * {@link SteppingController#MAX_STEP_DELAY} has been reached.
	 * 
	 * @see SteppingController#addSteppingControlParticipant(ISteppingControlParticipant)
	 * @see SteppingController#removeSteppingControlParticipant(ISteppingControlParticipant)
	 */
	public interface ISteppingControlParticipant {
	}

	private static class StepRequest {
        StepType fStepType;
        StepRequest(StepType type) {
            fStepType = type;
        }
    }

	private final DsfSession fSession;
	private final DsfServicesTracker fServicesTracker;

    private IRunControl fRunControl;
    private int fQueueDepth = STEP_QUEUE_DEPTH;
    
    private final Map<IExecutionDMContext,List<StepRequest>> fStepQueues = new HashMap<IExecutionDMContext,List<StepRequest>>();
    private final Map<IExecutionDMContext,Boolean> fTimedOutFlags = new HashMap<IExecutionDMContext,Boolean>();
    private final Map<IExecutionDMContext,ScheduledFuture<?>> fTimedOutFutures = new HashMap<IExecutionDMContext,ScheduledFuture<?>>();

	/**
	 * Records the time of the last step for an execution context.
	 */
	private final Map<IExecutionDMContext, Long> fLastStepTimes= new HashMap<IExecutionDMContext, Long>();

	/**
	 * Minimum step interval in milliseconds.
	 */
	private int fMinStepInterval= 0;

	/**
	 * Whether synchronized stepping is enabled.
	 */
	private boolean fSynchronizedStepping;
	
	/**
	 * Map of execution contexts for which a step is in progress.
	 */
	private final Map<IExecutionDMContext, List<ISteppingControlParticipant>> fStepInProgress = new HashMap<IExecutionDMContext, List<ISteppingControlParticipant>>();

	/**
	 * List of registered stepping control participants.
	 */
	private final List<ISteppingControlParticipant> fParticipants = Collections.synchronizedList(new ArrayList<ISteppingControlParticipant>());

	/**
	 * Property change listener.  It updates the stepping control settings.
	 */
	private IPropertyChangeListener fPreferencesListener;

    public SteppingController(DsfSession session) {
        fSession = session;
        fServicesTracker = new DsfServicesTracker(DsfDebugUIPlugin.getBundleContext(), session.getId());
        
        final IPreferenceStore store= DsfDebugUIPlugin.getDefault().getPreferenceStore();

        fPreferencesListener = new IPropertyChangeListener() {
            public void propertyChange(final PropertyChangeEvent event) {
                handlePropertyChanged(store, event);
            }};
        store.addPropertyChangeListener(fPreferencesListener);
        
        enableSynchronizedStepping(store.getBoolean(IDsfDebugUIConstants.PREF_SYNCHRONIZED_STEPPING_ENABLE));
        setMinimumStepInterval(store.getInt(IDsfDebugUIConstants.PREF_MIN_STEP_INTERVAL));
    }

    public void dispose() {
    	if (fRunControl != null) {
    		getSession().removeServiceEventListener(this);
    	}
    	
        IPreferenceStore store= DsfDebugUIPlugin.getDefault().getPreferenceStore();
        store.removePropertyChangeListener(fPreferencesListener);

        fServicesTracker.dispose();
    }

    /**
     * Enables or disables synchronized stepping mode.
     * In synchronized mode, after a step command is issued,
     * subsequent steps are blocked for the execution context
     * until {@link #doneStepping()} is called to indicate completion
     * of the processing for the last step.
     * 
     * @param enable
     */
    public void enableSynchronizedStepping(boolean enable) {
    	fSynchronizedStepping = enable;
    }

    /**
     * Configure the minimum time (in milliseconds) to wait between steps.
     * 
     * @param interval
     */
    public void setMinimumStepInterval(int interval) {
    	fMinStepInterval = interval;
    }

	/**
	 * Register given stepping control participant.
	 * <p>
	 * Participants are obliged to call
	 * {@link #doneStepping(IExecutionDMContext, ISteppingControlParticipant)}
	 * when they have received and completed processing an
	 * {@link ISuspendedDMEvent}. If synchronized stepping is enabled, further
	 * stepping is disabled until all participants have indicated completion of
	 * processing the event.
	 * </p>
	 * 
	 * @param participant
	 */
    public void addSteppingControlParticipant(ISteppingControlParticipant participant) {
    	fParticipants.add(participant);
    }

    /**
     * Unregister given stepping control participant.
     * 
     * @param participant
     */
    public void removeSteppingControlParticipant(final ISteppingControlParticipant participant) {
    	fParticipants.remove(participant);
    }

    /**
     * Indicate that participant has completed processing of the last step.
     * 
     * @param execCtx
     */
    public void doneStepping(final IExecutionDMContext execCtx, final ISteppingControlParticipant participant) {
    	List<ISteppingControlParticipant> participants = fStepInProgress.get(execCtx);
    	if (participants != null) {
    		participants.remove(participant);
    		if (participants.isEmpty()) {
    			doneStepping(execCtx);
    		}
    	} else {
    		for (IExecutionDMContext disabledCtx : fStepInProgress.keySet()) {
    			if (DMContexts.isAncestorOf(disabledCtx, execCtx)) {
    				participants = fStepInProgress.get(disabledCtx);
    		    	if (participants != null) {
    		    		participants.remove(participant);
    		    		if (participants.isEmpty()) {
    		    			doneStepping(disabledCtx);
    		    		}
    		    	}
    			}
    		}
    	}
    }

	public DsfSession getSession() {
		return fSession;
	}

	/**
	 * All access to this class should happen through this executor.
	 * @return the executor this class is confined to
	 */
	public DsfExecutor getExecutor() {
		return getSession().getExecutor();
	}
	
	private DsfServicesTracker getServicesTracker() {
		return fServicesTracker;
	}

	private IRunControl getRunControl() {
		if (fRunControl == null) {
	        fRunControl = getServicesTracker().getService(IRunControl.class);
	        getSession().addServiceEventListener(this, null);
		}
		return fRunControl;
	}

	/**
     * Checks whether a step command can be queued up for given context.
     */
    public void canEnqueueStep(IExecutionDMContext execCtx, StepType stepType, DataRequestMonitor<Boolean> rm) {
        if (doCanEnqueueStep(execCtx, stepType)) {
            rm.setData(true);
            rm.done();
        } else {
            getRunControl().canStep(execCtx, stepType, rm);
        }
    }

    private boolean doCanEnqueueStep(IExecutionDMContext execCtx, StepType stepType) {
        return getRunControl().isStepping(execCtx) && !isSteppingTimedOut(execCtx); 
    }

    /**
     * Check whether the next step on the given execution context should be delayed
     * based on the configured step delay.
     * 
	 * @param execCtx
	 * @return <code>true</code> if the step should be delayed
	 */
	private boolean shouldDelayStep(IExecutionDMContext execCtx) {
        return getStepDelay(execCtx) > 0;
	}

	/**
	 * Compute the delay in milliseconds before the next step for the given context may be executed.
	 * 
	 * @param execCtx
	 * @return  the number of milliseconds before the next possible step
	 */
	private int getStepDelay(IExecutionDMContext execCtx) {
		if (fMinStepInterval > 0) {
	        for (IExecutionDMContext lastStepCtx : fLastStepTimes.keySet()) {
	            if (execCtx.equals(lastStepCtx) || DMContexts.isAncestorOf(execCtx, lastStepCtx)) {
	                long now = System.currentTimeMillis();
					int delay= (int) (fLastStepTimes.get(lastStepCtx) + fMinStepInterval - now);
					return Math.max(delay, 0);
	            }
	        }
		}
        return 0;
	}

	private void updateLastStepTime(IExecutionDMContext execCtx) {
        long now = System.currentTimeMillis();
		fLastStepTimes.put(execCtx, now);
        for (IExecutionDMContext lastStepCtx : fLastStepTimes.keySet()) {
            if (!execCtx.equals(lastStepCtx) && DMContexts.isAncestorOf(execCtx, lastStepCtx)) {
				fLastStepTimes.put(lastStepCtx, now);
            }
        }
	}

	private long getLastStepTime(IExecutionDMContext execCtx) {
		if (fLastStepTimes.containsKey(execCtx)) {
			return fLastStepTimes.get(execCtx);
		}
		for (IExecutionDMContext lastStepCtx : fLastStepTimes.keySet()) {
			if (DMContexts.isAncestorOf(execCtx, lastStepCtx)) {
				return fLastStepTimes.get(lastStepCtx);
			}
		}
		return 0;
	}

	/**
     * Returns the number of step commands that are queued for given execution
     * context.
     */
    public int getPendingStepCount(IExecutionDMContext execCtx) {
        List<StepRequest> stepQueue = fStepQueues.get(execCtx);
        if (stepQueue == null) return 0;
        return stepQueue.size();
    }

    /**
     * Adds a step command to the execution queue for given context.
     * @param execCtx Execution context that should perform the step. 
     * @param stepType Type of step to execute.
     */
    public void enqueueStep(final IExecutionDMContext execCtx, final StepType stepType) {
    	if (shouldDelayStep(execCtx)) {
    		if (doCanEnqueueStep(execCtx, stepType)) {
	            doEnqueueStep(execCtx, stepType);
	            if (!getRunControl().isStepping(execCtx)) {
	            	processStepQueue(execCtx);
	            }
    		}
    	} else {
	        getRunControl().canStep(
	            execCtx, stepType, new DataRequestMonitor<Boolean>(getExecutor(), null) {
	                @Override
	                protected void handleCompleted() {
	                    if (isSuccess() && getData()) {
	                    	if (isSteppingDisabled(execCtx)) {
		                        doEnqueueStep(execCtx, stepType);
	                    	} else {
	                    		doStep(execCtx, stepType);
	                    	}
	                    } else if (doCanEnqueueStep(execCtx, stepType)) {
	                        doEnqueueStep(execCtx, stepType);
	                    }
	                }
	            });
    	}
    }

	private void doStep(final IExecutionDMContext execCtx, final StepType stepType) {
		if (fSynchronizedStepping) {
			disableStepping(execCtx);
		}
        updateLastStepTime(execCtx);
		getRunControl().step(execCtx, stepType, new RequestMonitor(getExecutor(), null));
	}

	/**
	 * Enqueue the given step for later execution.
	 * 
	 * @param execCtx
	 * @param stepType
	 */
	private void doEnqueueStep(final IExecutionDMContext execCtx, final StepType stepType) {
		List<StepRequest> stepQueue = fStepQueues.get(execCtx);
		if (stepQueue == null) {
		    stepQueue = new LinkedList<StepRequest>();
		    fStepQueues.put(execCtx, stepQueue);
		}
		if (stepQueue.size() < fQueueDepth) {
		    stepQueue.add(new StepRequest(stepType));
		}
	}

    /**
     * Returns whether the step instruction for the given context has timed out.
     */
    public boolean isSteppingTimedOut(IExecutionDMContext execCtx) {
        for (IExecutionDMContext timedOutCtx : fTimedOutFlags.keySet()) {
            if (execCtx.equals(timedOutCtx) || DMContexts.isAncestorOf(execCtx, timedOutCtx)) {
                return fTimedOutFlags.get(timedOutCtx);
            }
        }
        return false;
    }
    
	/**
	 * Process next step on queue if any.
	 * @param execCtx
	 */
	private void processStepQueue(final IExecutionDMContext execCtx) {
		if (isSteppingDisabled(execCtx)) {
			return;
		}
		if (fStepQueues.containsKey(execCtx)) {
			final int stepDelay = getStepDelay(execCtx);
			if (stepDelay > 0) {
				getExecutor().schedule(new DsfRunnable() {
					public void run() {
						processStepQueue(execCtx);
					}
				}, stepDelay, TimeUnit.MILLISECONDS);
				return;
			}
            List<StepRequest> queue = fStepQueues.get(execCtx);
            final StepRequest request = queue.remove(queue.size() - 1);
            if (queue.isEmpty()) fStepQueues.remove(execCtx);
            getRunControl().canStep(
                execCtx, request.fStepType, 
                new DataRequestMonitor<Boolean>(getExecutor(), null) {
                    @Override
                    protected void handleCompleted() {
                        if (isSuccess() && getData()) {
                    		doStep(execCtx, request.fStepType);
                        } else {
                            // For whatever reason we can't step anymore, so clear out
                            // the step queue.
                            fStepQueues.remove(execCtx);
                        }
                    }
                });
        }
	}

	/**
	 * Disable stepping for the given execution context.
	 * 
	 * @param execCtx
	 */
	private void disableStepping(IExecutionDMContext execCtx) {
        fStepInProgress.put(execCtx, new ArrayList<ISteppingControlParticipant>(fParticipants));
	}

    /**
     * Indicate that processing of the last step has completed and
     * the next step can be issued.
     * 
     * @param execCtx
     */
    private void doneStepping(final IExecutionDMContext execCtx) {
    	if (fSynchronizedStepping) {
    		enableStepping(execCtx);
    		processStepQueue(execCtx);
    	}
    }

	/**
	 * Enable stepping for the given execution context.
	 * 
	 * @param execCtx
	 */
	private void enableStepping(final IExecutionDMContext execCtx) {
        fStepInProgress.remove(execCtx);
		for (IExecutionDMContext disabledCtx : fStepInProgress.keySet()) {
			if (DMContexts.isAncestorOf(disabledCtx, execCtx)) {
				fStepInProgress.remove(disabledCtx);
			}
		}
	}

	private boolean isSteppingDisabled(IExecutionDMContext execCtx) {
		if (fSynchronizedStepping) {
	        boolean disabled= fStepInProgress.containsKey(execCtx);
	        if (!disabled) {
		        for (IExecutionDMContext disabledCtx : fStepInProgress.keySet()) {
					if (DMContexts.isAncestorOf(execCtx, disabledCtx)) {
						disabled = true;
						break;
					}
				}
	        }
	        if (disabled) {
	        	long now = System.currentTimeMillis();
	        	long lastStepTime = getLastStepTime(execCtx);
	        	if (now - lastStepTime > MAX_STEP_DELAY) {
	        		enableStepping(execCtx);
	        		disabled = false;
	        	}
	        }
	        return disabled;
		}
        return false;
	}

	protected void handlePropertyChanged(final IPreferenceStore store, final PropertyChangeEvent event) {
		String property = event.getProperty();
		if (IDsfDebugUIConstants.PREF_SYNCHRONIZED_STEPPING_ENABLE.equals(property)) {
			enableSynchronizedStepping(store.getBoolean(property));
		} else if (IDsfDebugUIConstants.PREF_MIN_STEP_INTERVAL.equals(property)) {
			setMinimumStepInterval(store.getInt(property));
		}
	}


    ///////////////////////////////////////////////////////////////////////////

    @DsfServiceEventHandler 
    public void eventDispatched(final ISuspendedDMEvent e) {
        // Take care of the stepping time out
        fTimedOutFlags.remove(e.getDMContext());
        ScheduledFuture<?> future = fTimedOutFutures.remove(e.getDMContext()); 
        if (future != null) future.cancel(false);
        
        // Check if there's a step pending, if so execute it
        processStepQueue(e.getDMContext());
    }

    @DsfServiceEventHandler 
    public void eventDispatched(final IResumedDMEvent e) {
        if (e.getReason().equals(StateChangeReason.STEP)) {
            fTimedOutFlags.put(e.getDMContext(), Boolean.FALSE);
            // We shouldn't have a stepping timeout running unless we get two 
            // stepping events in a row without a suspended, which would be a 
            // protocol error.
            assert !fTimedOutFutures.containsKey(e.getDMContext());
            fTimedOutFutures.put(
                e.getDMContext(), 
                getExecutor().schedule(
                    new DsfRunnable() { public void run() {
                        fTimedOutFutures.remove(e.getDMContext());

                        if (getSession().isActive()) {
                            // Issue the stepping time-out event.
                            getSession().dispatchEvent(
                                new SteppingTimedOutEvent(e.getDMContext()), 
                                null);
                        }
                    }},
                    STEPPING_TIMEOUT, TimeUnit.MILLISECONDS)
                );
            
        } 
    }    

    @DsfServiceEventHandler 
    public void eventDispatched(SteppingTimedOutEvent e) {
        fTimedOutFlags.put(e.getDMContext(), Boolean.TRUE);
        enableStepping(e.getDMContext());
    }
    
    
}