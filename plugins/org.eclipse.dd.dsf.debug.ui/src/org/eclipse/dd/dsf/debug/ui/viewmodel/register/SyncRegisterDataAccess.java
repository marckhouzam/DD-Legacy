/*******************************************************************************
 * Copyright (c) 2007 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/

package org.eclipse.dd.dsf.debug.ui.viewmodel.register;

import java.util.concurrent.ExecutionException;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.DsfExecutor;
import org.eclipse.dd.dsf.concurrent.Query;
import org.eclipse.dd.dsf.concurrent.ThreadSafeAndProhibitedFromDsfExecutor;
import org.eclipse.dd.dsf.debug.service.IRegisters;
import org.eclipse.dd.dsf.debug.service.IFormattedValues.FormattedValueDMContext;
import org.eclipse.dd.dsf.debug.service.IFormattedValues.FormattedValueDMData;
import org.eclipse.dd.dsf.debug.service.IFormattedValues.IFormattedDataDMContext;
import org.eclipse.dd.dsf.debug.service.IRegisters.IBitFieldDMContext;
import org.eclipse.dd.dsf.debug.service.IRegisters.IBitFieldDMData;
import org.eclipse.dd.dsf.debug.service.IRegisters.IMnemonic;
import org.eclipse.dd.dsf.debug.service.IRegisters.IRegisterDMContext;
import org.eclipse.dd.dsf.debug.service.IRegisters.IRegisterDMData;
import org.eclipse.dd.dsf.debug.ui.DsfDebugUIPlugin;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.dsf.service.IDsfService;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.util.tracker.ServiceTracker;

@ThreadSafeAndProhibitedFromDsfExecutor("")
public class SyncRegisterDataAccess {

    /**
     * Need to use the OSGi service tracker here (instead of DsfServiceTracker),
     * because we're accessing it in non-dispatch thread. DsfServiceTracker is
     * not thread-safe.
     */
    private ServiceTracker fServiceTracker;

    private synchronized IRegisters getService(String filter) {

        if (fServiceTracker == null) {
            try {
                fServiceTracker = new ServiceTracker(DsfDebugUIPlugin
                        .getBundleContext(), DsfDebugUIPlugin.getBundleContext()
                        .createFilter(filter), null);
                fServiceTracker.open();
            } catch (InvalidSyntaxException e) {
                assert false : "Invalid filter in DMC: " + filter; //$NON-NLS-1$
                return null;
            }
        } else {
            /*
             * All of the DMCs that this cell modifier is invoked for should
             * originate from the same service. This assertion checks this
             * assumption by comparing the service reference in the tracker to
             * the filter string in the DMC.
             */
            try {
                assert DsfDebugUIPlugin.getBundleContext().createFilter(filter)
                        .match(fServiceTracker.getServiceReference());
            } catch (InvalidSyntaxException e) {
            }
        }
        return (IRegisters) fServiceTracker.getService();
    }
    
    public void dispose() {
        if ( fServiceTracker != null ) {
            fServiceTracker.close();
        }
    }

    public class GetBitFieldValueQuery extends Query<IBitFieldDMData> {

        private IBitFieldDMContext fDmc;

        public GetBitFieldValueQuery(DsfExecutor executor, IBitFieldDMContext dmc) {
            super(executor);
            fDmc = dmc;
        }

        @Override
        protected void execute(final DataRequestMonitor<IBitFieldDMData> rm) {
            /*
             * Guard agains the session being disposed. If session is disposed
             * it could mean that the executor is shut-down, which in turn could
             * mean that we can't complete the RequestMonitor argument. in that
             * case, cancel to notify waiting thread.
             */
            final DsfSession session = DsfSession.getSession(fDmc.getSessionId());
            if (session == null) {
                cancel(false);
                return;
            }

            IRegisters service = getService(fDmc.getServiceFilter());
            if (service == null) {
                rm.setStatus(new Status(IStatus.ERROR, DsfDebugUIPlugin.PLUGIN_ID, IDsfService.INVALID_STATE, "Service not available", null)); //$NON-NLS-1$
                rm.done();
                return;
            }

            service.getModelData(fDmc, new DataRequestMonitor<IBitFieldDMData>(session.getExecutor(), rm) {
                @Override
                protected void handleCompleted() {
                    /*
                     * We're in another dispatch, so we must guard against
                     * executor shutdown again.
                     */
                    if (!DsfSession.isSessionActive(session.getId())) {
                        GetBitFieldValueQuery.this.cancel(false);
                        return;
                    }
                    super.handleCompleted();
                }

                @Override
                protected void handleOK() {
                    /*
                     * All good set return value.
                     */
                    rm.setData(getData());
                    rm.done();
                }
            });
        }
    }

    public IBitFieldDMContext getBitFieldDMC(Object element) {
        if (element instanceof IAdaptable) {
            return (IBitFieldDMContext) ((IAdaptable) element).getAdapter(IBitFieldDMContext.class);
        }
        return null;
    }

    public IBitFieldDMData readBitField(Object element) {
        /*
         * Get the DMC and the session. If element is not an register DMC, or
         * session is stale, then bail out.
         */
        IBitFieldDMContext dmc = getBitFieldDMC(element);
        if (dmc == null) return null;
        DsfSession session = DsfSession.getSession(dmc.getSessionId());
        if (session == null) return null;

        /*
         * Create the query to request the value from service. Note: no need to
         * guard agains RejectedExecutionException, because
         * DsfSession.getSession() above would only return an active session.
         */
        GetBitFieldValueQuery query = new GetBitFieldValueQuery(session.getExecutor(), dmc);
        session.getExecutor().execute(query);

        /*
         * Now we have the data, go and get it. Since the call is completed now
         * the ".get()" will not suspend it will immediately return with the
         * data.
         */
        try {
            return query.get();
        } catch (InterruptedException e) {
            assert false;
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }

    public class SetBitFieldValueQuery extends Query<Object> {

        private IBitFieldDMContext fDmc;
        private String fValue;
        private String fFormatId;

        public SetBitFieldValueQuery(DsfExecutor executor, IBitFieldDMContext dmc, String value, String formatId) {
            super(executor);
            fDmc = dmc;
            fValue = value;
            fFormatId = formatId;
        }

        @Override
        protected void execute(final DataRequestMonitor<Object> rm) {
            /*
             * We're in another dispatch, so we must guard against executor
             * shutdown again.
             */
            final DsfSession session = DsfSession.getSession(fDmc.getSessionId());
            if (session == null) {
                cancel(false);
                return;
            }

            /*
             * Guard against a disposed service
             */
            IRegisters service = getService(fDmc.getServiceFilter());
            if (service == null) {
                rm.setStatus(new Status(IStatus.ERROR, DsfDebugUIPlugin.PLUGIN_ID, IDsfService.INVALID_STATE, "Service unavailable", null)); //$NON-NLS-1$
                rm.done();
                return;
            }

            /*
             * Write the bit field using a string/format style.
             */
            service.writeBitField(
               fDmc, 
               fValue, 
               fFormatId,
               new DataRequestMonitor<IBitFieldDMData>(session.getExecutor(), rm) {
                   @Override
                   protected void handleCompleted() {
                       /*
                        * We're in another dispatch, so we must guard
                        * against executor shutdown again.
                        */
                       if (!DsfSession.isSessionActive(session.getId())) {
                           SetBitFieldValueQuery.this.cancel(false);
                           return;
                        }
                        super.handleCompleted();
                    }

                    @Override
                    protected void handleOK() {
                        /*
                         * All good set return value.
                         */
                        rm.setData(new Object());
                        rm.done();
                    }
                }
            );
        }
    }

    public void writeBitField(Object element, String value, String formatId) {

        /*
         * Get the DMC and the session. If element is not an register DMC, or
         * session is stale, then bail out.
         */
        IBitFieldDMContext dmc = getBitFieldDMC(element);
        if (dmc == null) return;
        DsfSession session = DsfSession.getSession(dmc.getSessionId());
        if (session == null) return;

        /*
         * Create the query to write the value to the service. Note: no need to
         * guard agains RejectedExecutionException, because
         * DsfSession.getSession() above would only return an active session.
         */
        SetBitFieldValueQuery query = new SetBitFieldValueQuery(session.getExecutor(), dmc, value, formatId);
        session.getExecutor().execute(query);

        /*
         * Now we have the data, go and get it. Since the call is completed now
         * the ".get()" will not suspend it will immediately return with the
         * data.
         */
        try {
            /*
             * Return value is irrelevant, any error would come through with an
             * exception.
             */
            query.get();
        } catch (InterruptedException e) {
            assert false;
        } catch (ExecutionException e) {
            assert false;
            /*
             * View must be shutting down, no need to show erro dialog.
             */
        }
    }

    public class SetBitFieldValueMnemonicQuery extends Query<Object> {

        IBitFieldDMContext fDmc;

        IMnemonic fMnemonic;

        public SetBitFieldValueMnemonicQuery(DsfExecutor executor, IBitFieldDMContext dmc, IMnemonic mnemonic) {
            super(executor);
            fDmc = dmc;
            fMnemonic = mnemonic;
        }

        @Override
        protected void execute(final DataRequestMonitor<Object> rm) {
            /*
             * We're in another dispatch, so we must guard against executor
             * shutdown again.
             */
            final DsfSession session = DsfSession.getSession(fDmc.getSessionId());
            if (session == null) {
                cancel(false);
                return;
            }

            /*
             * Guard against a disposed service
             */
            IRegisters service = getService(fDmc.getServiceFilter());
            if (service == null) {
                rm.setStatus(new Status(IStatus.ERROR, DsfDebugUIPlugin.PLUGIN_ID, IDsfService.INVALID_STATE, "Service unavailable", null)); //$NON-NLS-1$
                rm.done();
                return;
            }

            /*
             * Write the bit field using the mnemonic style.
             */
            service.writeBitField(
                fDmc, 
                fMnemonic,
                new DataRequestMonitor<IBitFieldDMData>(session.getExecutor(), rm) {
                    @Override
                    protected void handleCompleted() {
                        /*
                         * We're in another dispatch, so we must guard
                         * against executor shutdown again.
                         */
                        if (!DsfSession.isSessionActive(session.getId())) {
                            SetBitFieldValueMnemonicQuery.this.cancel(false);
                            return;
                        }
                        super.handleCompleted();
                    }

                    @Override
                    protected void handleOK() {
                        /*
                         * All good set return value.
                         */
                        rm.setData(new Object());
                        rm.done();
                    }
                }
            );
        }
    }

    public void writeBitField(Object element, IMnemonic mnemonic) {

        /*
         * Get the DMC and the session. If element is not an register DMC, or
         * session is stale, then bail out.
         */
        IBitFieldDMContext dmc = getBitFieldDMC(element);
        if (dmc == null) return;
        DsfSession session = DsfSession.getSession(dmc.getSessionId());
        if (session == null) return;

        /*
         * Create the query to write the value to the service. Note: no need to
         * guard agains RejectedExecutionException, because
         * DsfSession.getSession() above would only return an active session.
         */
        SetBitFieldValueMnemonicQuery query = new SetBitFieldValueMnemonicQuery( session.getExecutor(), dmc, mnemonic);
        session.getExecutor().execute(query);

        /*
         * Now we have the data, go and get it. Since the call is completed now
         * the ".get()" will not suspend it will immediately return with the
         * data.
         */
        try {
            /*
             * Return value is irrelevant, any error would come through with an
             * exception.
             */
            query.get();
        } catch (InterruptedException e) {
            assert false;
        } catch (ExecutionException e) {
            /*
             * View must be shutting down, no need to show erro dialog.
             */
        }
    }

    public IRegisterDMContext getRegisterDMC(Object element) {
        if (element instanceof IAdaptable) {
            return (IRegisterDMContext) ((IAdaptable) element).getAdapter(IRegisterDMContext.class);
        }
        return null;
    }

    public IFormattedDataDMContext<?> getFormattedDMC(Object element) {
        if (element instanceof IAdaptable) {
            return (IFormattedDataDMContext) ((IAdaptable) element).getAdapter(IFormattedDataDMContext.class);
        }
        return null;
    }

    public class GetRegisterValueQuery extends Query<IRegisterDMData> {

        IRegisterDMContext fDmc;

        public GetRegisterValueQuery(DsfExecutor executor, IRegisterDMContext dmc) {
            super(executor);
            fDmc = dmc;
        }

        @Override
        protected void execute(final DataRequestMonitor<IRegisterDMData> rm) {
            /*
             * Guard agains the session being disposed. If session is disposed
             * it could mean that the executor is shut-down, which in turn could
             * mean that we can't complete the RequestMonitor argument. in that
             * case, cancel to notify waiting thread.
             */
            final DsfSession session = DsfSession.getSession(fDmc.getSessionId());
            if (session == null) {
                cancel(false);
                return;
            }

            IRegisters service = getService(fDmc.getServiceFilter());
            if (service == null) {
                rm.setStatus(new Status(IStatus.ERROR, DsfDebugUIPlugin.PLUGIN_ID, IDsfService.INVALID_STATE, "Service not available", null)); //$NON-NLS-1$
                rm.done();
                return;
            }

            service.getModelData(fDmc, new DataRequestMonitor<IRegisterDMData>( session.getExecutor(), rm) {
                @Override
                protected void handleCompleted() {
                    /*
                     * We're in another dispatch, so we must guard against
                     * executor shutdown again.
                     */
                    if (!DsfSession.isSessionActive(session.getId())) {
                        GetRegisterValueQuery.this.cancel(false);
                        return;
                    }
                    super.handleCompleted();
                }

                @Override
                protected void handleOK() {
                    /*
                     * All good set return value.
                     */
                    rm.setData(getData());
                    rm.done();
                }
            });
        }
    }

    public IRegisterDMData readRegister(Object element) {
        /*
         * Get the DMC and the session. If element is not an register DMC, or
         * session is stale, then bail out.
         */
        IRegisterDMContext dmc = getRegisterDMC(element);
        if (dmc == null) return null;
        DsfSession session = DsfSession.getSession(dmc.getSessionId());
        if (session == null) return null;

        /*
         * Create the query to request the value from service. Note: no need to
         * guard agains RejectedExecutionException, because
         * DsfSession.getSession() above would only return an active session.
         */
        GetRegisterValueQuery query = new GetRegisterValueQuery(session.getExecutor(), dmc);
        session.getExecutor().execute(query);

        /*
         * Now we have the data, go and get it. Since the call is completed now
         * the ".get()" will not suspend it will immediately return with the
         * data.
         */
        try {
            return query.get();
        } catch (InterruptedException e) {
            assert false;
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }

    public class SetRegisterValueQuery extends Query<Object> {

        private IRegisterDMContext fDmc;
        private String fValue;
        private String fFormatId;

        public SetRegisterValueQuery(DsfExecutor executor, IRegisterDMContext dmc, String value, String formatId) {
            super(executor);
            fDmc = dmc;
            fValue = value;
            fFormatId = formatId;
        }

        @Override
        protected void execute(final DataRequestMonitor<Object> rm) {
            /*
             * We're in another dispatch, so we must guard against executor
             * shutdown again.
             */
            final DsfSession session = DsfSession.getSession(fDmc.getSessionId());
            if (session == null) {
                cancel(false);
                return;
            }

            /*
             * Guard against a disposed service
             */
            IRegisters service = getService(fDmc.getServiceFilter());
            if (service == null) {
                rm.setStatus(new Status(IStatus.ERROR, DsfDebugUIPlugin.PLUGIN_ID, IDsfService.INVALID_STATE, "Service unavailable", null)); //$NON-NLS-1$
                rm.done();
                return;
            }

            /*
             * The interface does not currently have a write function. It needs
             * to and now would seem to be the time to add it.
             */
            /*
             * Write the bit field using a string/format style.
             */
            service.writeRegister(
                fDmc, 
                fValue, 
                fFormatId,
                new DataRequestMonitor<IBitFieldDMData>(session.getExecutor(), rm) {
                    @Override
                    protected void handleCompleted() {
                        /*
                         * We're in another dispatch, so we must guard
                         * against executor shutdown again.
                         */
                        if (!DsfSession.isSessionActive(session.getId())) {
                            SetRegisterValueQuery.this.cancel(false);
                            return;
                        }
                        super.handleCompleted();
                    }

                    @Override
                    protected void handleOK() {
                        /*
                         * All good set return value.
                         */
                        rm.setData(new Object());
                        rm.done();
                    }
                }
            );
        }
    }

    public void writeRegister(Object element, String value,
            String formatId) {

        /*
         * Get the DMC and the session. If element is not an register DMC, or
         * session is stale, then bail out.
         */
        IRegisterDMContext dmc = getRegisterDMC(element);
        if (dmc == null) return;
        DsfSession session = DsfSession.getSession(dmc.getSessionId());
        if (session == null) return;

        /*
         * Create the query to write the value to the service. Note: no need to
         * guard agains RejectedExecutionException, because
         * DsfSession.getSession() above would only return an active session.
         */
        SetRegisterValueQuery query = new SetRegisterValueQuery(session.getExecutor(), dmc, value, formatId);
        session.getExecutor().execute(query);

        /*
         * Now we have the data, go and get it. Since the call is completed now
         * the ".get()" will not suspend it will immediately return with the
         * data.
         */
        try {
            /*
             * Return value is irrelevant, any error would come through with an
             * exception.
             */
            query.get();
        } catch (InterruptedException e) {
            assert false;
        } catch (ExecutionException e) {
            /*
             * View must be shutting down, no need to show erro dialog.
             */
        }
    }

    public class GetSupportFormatsValueQuery extends Query<Object> {

        IFormattedDataDMContext<?> fDmc;

        public GetSupportFormatsValueQuery(DsfExecutor executor, IFormattedDataDMContext<?> dmc) {
            super(executor);
            fDmc = dmc;
        }

        @Override
        protected void execute(final DataRequestMonitor<Object> rm) {
            /*
             * We're in another dispatch, so we must guard against executor
             * shutdown again.
             */
            final DsfSession session = DsfSession.getSession(fDmc.getSessionId());
            if (session == null) {
                cancel(false);
                return;
            }

            /*
             * Guard against a disposed service
             */
            IRegisters service = getService(fDmc.getServiceFilter());
            if (service == null) {
                rm.setStatus(new Status(IStatus.ERROR, DsfDebugUIPlugin.PLUGIN_ID, IDsfService.INVALID_STATE, "Service unavailable", null)); //$NON-NLS-1$
                rm.done();
                return;
            }

            /*
             * Write the bit field using a string/format style.
             */
            service.getAvailableFormattedValues(
                fDmc,
                new DataRequestMonitor<String[]>(session.getExecutor(), rm) {
                    @Override
                    protected void handleCompleted() {
                        /*
                         * We're in another dispatch, so we must
                         * guard against executor shutdown again.
                         */
                        if (!DsfSession.isSessionActive(session.getId())) {
                            GetSupportFormatsValueQuery.this.cancel(false);
                            return;
                        }
                        super.handleCompleted();
                    }

                    @Override
                    protected void handleOK() {
                        /*
                         * All good set return value.
                         */
                        rm.setData(new Object());
                        rm.done();
                    }
                }
            );
        }
    }

    public String[] getSupportedFormats(Object element) {

        /*
         * Get the DMC and the session. If element is not an register DMC, or
         * session is stale, then bail out.
         */
        IFormattedDataDMContext<?> dmc = getFormattedDMC(element);
        if (dmc == null) return null;
        DsfSession session = DsfSession.getSession(dmc.getSessionId());
        if (session == null) return null;
        
        /*
         * Create the query to write the value to the service. Note: no need to
         * guard agains RejectedExecutionException, because
         * DsfSession.getSession() above would only return an active session.
         */
        GetSupportFormatsValueQuery query = new GetSupportFormatsValueQuery( session.getExecutor(), dmc);
        session.getExecutor().execute(query);

        /*
         * Now we have the data, go and get it. Since the call is completed now
         * the ".get()" will not suspend it will immediately return with the
         * data.
         */
        try {
            return (String[]) query.get();
        } catch (InterruptedException e) {
            assert false;
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }

    public class GetFormattedValueValueQuery extends Query<Object> {

        private IFormattedDataDMContext<?> fDmc;
        private String fFormatId;

        public GetFormattedValueValueQuery(DsfExecutor executor, IFormattedDataDMContext<?> dmc, String formatId) {
            super(executor);
            fDmc = dmc;
            fFormatId = formatId;
        }

        @Override
        protected void execute(final DataRequestMonitor<Object> rm) {
            /*
             * We're in another dispatch, so we must guard against executor
             * shutdown again.
             */
            final DsfSession session = DsfSession.getSession(fDmc.getSessionId());
            if (session == null) {
                cancel(false);
                return;
            }

            /*
             * Guard against a disposed service
             */
            IRegisters service = getService(fDmc.getServiceFilter());
            if (service == null) {
                rm .setStatus(new Status(IStatus.ERROR, DsfDebugUIPlugin.PLUGIN_ID, IDsfService.INVALID_STATE, "Service unavailable", null)); //$NON-NLS-1$
                rm.done();
                return;
            }

            /*
             * Convert to the proper formatting DMC then go get the formatted value.
             */
            
            FormattedValueDMContext formDmc = service.getFormattedValue(fDmc, fFormatId);
            
            service.getModelData(formDmc, new DataRequestMonitor<FormattedValueDMData>( session.getExecutor(), rm) {
                @Override
                protected void handleCompleted() {
                    /*
                     * We're in another dispatch, so we must guard against executor shutdown again.
                     */
                    if (!DsfSession.isSessionActive(session.getId())) {
                        GetFormattedValueValueQuery.this.cancel(false);
                        return;
                    }
                    super.handleCompleted();
                }

                @Override
                protected void handleOK() {
                    /*
                     * All good set return value.
                     */
                    rm.setData(getData().getFormattedValue());
                    rm.done();
                }
            });
        }
    }

    public String getFormattedValue(Object element, String formatId) {

        /*
         * Get the DMC and the session. If element is not an register DMC, or
         * session is stale, then bail out.
         */
        IFormattedDataDMContext<?> dmc = getFormattedDMC(element);
        if (dmc == null) return null;
        DsfSession session = DsfSession.getSession(dmc.getSessionId());
        if (session == null) return null;
        
        /*
         * Create the query to write the value to the service. Note: no need to
         * guard agains RejectedExecutionException, because
         * DsfSession.getSession() above would only return an active session.
         */
        GetFormattedValueValueQuery query = new GetFormattedValueValueQuery(session.getExecutor(), dmc, formatId);
        session.getExecutor().execute(query);

        /*
         * Now we have the data, go and get it. Since the call is completed now
         * the ".get()" will not suspend it will immediately return with the
         * data.
         */
        try {
            return (String) query.get();
        } catch (InterruptedException e) {
            assert false;
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }
}