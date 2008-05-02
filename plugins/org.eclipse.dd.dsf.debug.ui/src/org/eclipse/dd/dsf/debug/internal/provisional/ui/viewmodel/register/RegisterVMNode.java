/*******************************************************************************
 * Copyright (c) 2006 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.register;

import java.util.concurrent.RejectedExecutionException;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.dd.dsf.concurrent.DataRequestMonitor;
import org.eclipse.dd.dsf.concurrent.DsfRunnable;
import org.eclipse.dd.dsf.concurrent.IDsfStatusConstants;
import org.eclipse.dd.dsf.concurrent.ImmediateExecutor;
import org.eclipse.dd.dsf.concurrent.RequestMonitor;
import org.eclipse.dd.dsf.datamodel.DMContexts;
import org.eclipse.dd.dsf.datamodel.IDMContext;
import org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.IDebugVMConstants;
import org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.expression.AbstractExpressionVMNode;
import org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.numberformat.IFormattedValuePreferenceStore;
import org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.numberformat.IFormattedValueVMContext;
import org.eclipse.dd.dsf.debug.internal.ui.DsfDebugUIPlugin;
import org.eclipse.dd.dsf.debug.service.IFormattedValues;
import org.eclipse.dd.dsf.debug.service.IMemory;
import org.eclipse.dd.dsf.debug.service.IRegisters;
import org.eclipse.dd.dsf.debug.service.IRunControl;
import org.eclipse.dd.dsf.debug.service.IFormattedValues.FormattedValueDMContext;
import org.eclipse.dd.dsf.debug.service.IFormattedValues.FormattedValueDMData;
import org.eclipse.dd.dsf.debug.service.IRegisters.IRegisterChangedDMEvent;
import org.eclipse.dd.dsf.debug.service.IRegisters.IRegisterDMContext;
import org.eclipse.dd.dsf.debug.service.IRegisters.IRegisterDMData;
import org.eclipse.dd.dsf.debug.service.IRegisters.IRegisterGroupDMData;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.dd.dsf.ui.viewmodel.IVMContext;
import org.eclipse.dd.dsf.ui.viewmodel.VMDelta;
import org.eclipse.dd.dsf.ui.viewmodel.datamodel.AbstractDMVMProvider;
import org.eclipse.dd.dsf.ui.viewmodel.datamodel.IDMVMContext;
import org.eclipse.debug.core.model.IExpression;
import org.eclipse.debug.internal.ui.DebugPluginImages;
import org.eclipse.debug.internal.ui.DebugUIPlugin;
import org.eclipse.debug.internal.ui.IInternalDebugUIConstants;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IChildrenUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IElementEditor;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IElementLabelProvider;
import org.eclipse.debug.internal.ui.viewers.model.provisional.ILabelUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IModelDelta;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IPresentationContext;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.debug.ui.actions.IWatchExpressionFactoryAdapter2;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.jface.viewers.CellEditor;
import org.eclipse.jface.viewers.ICellModifier;
import org.eclipse.jface.viewers.TextCellEditor;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.swt.widgets.Composite;

@SuppressWarnings("restriction")
public class RegisterVMNode extends AbstractExpressionVMNode 
    implements IElementEditor, IElementLabelProvider
{
    protected class RegisterVMC extends DMVMContext
        implements IFormattedValueVMContext
    {
        private IExpression fExpression;
        public RegisterVMC(IDMContext dmc) {
            super(dmc);
        }

        public void setExpression(IExpression expression) {
            fExpression = expression;
        }

        @Override
        @SuppressWarnings("unchecked") 
        public Object getAdapter(Class adapter) {
            if (fExpression != null && adapter.isAssignableFrom(fExpression.getClass())) {
                return fExpression;
            } else if (adapter.isAssignableFrom(IWatchExpressionFactoryAdapter2.class)) {
                return fRegisterExpressionFactory;
            } else {
                return super.getAdapter(adapter);
            }
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof RegisterVMC && super.equals(other)) {
                RegisterVMC otherReg = (RegisterVMC)other;
                return (otherReg.fExpression == null && fExpression == null) ||
                (otherReg.fExpression != null && otherReg.fExpression.equals(fExpression));
            }
            return false;
        }

        @Override
        public int hashCode() {
            return super.hashCode() + (fExpression != null ? fExpression.hashCode() : 0);
        }

        public IFormattedValuePreferenceStore getPreferenceStore() {
            return fFormattedPrefStore;
        }
    }

    protected class RegisterExpressionFactory implements IWatchExpressionFactoryAdapter2 {

        public boolean canCreateWatchExpression(Object element) {
            return element instanceof RegisterVMC;
        }

        /**
         * Expected format: GRP( GroupName ).REG( RegisterName )
         */
        public String createWatchExpression(Object element) throws CoreException {
            IRegisterGroupDMData groupData = fSyncRegisterDataAccess.getRegisterGroupDMData(element);
            IRegisterDMData registerData = fSyncRegisterDataAccess.getRegisterDMData(element);
            
            if (groupData != null && registerData != null) { 
            	StringBuffer exprBuf = new StringBuffer();
            	
            	exprBuf.append("GRP( ");  exprBuf.append(groupData.getName());    exprBuf.append(" )"); //$NON-NLS-1$ //$NON-NLS-2$
            	exprBuf.append(".REG( "); exprBuf.append(registerData.getName()); exprBuf.append(" )"); //$NON-NLS-1$ //$NON-NLS-2$
                
                return exprBuf.toString();
            }

            return null;            
        }
    }

    final protected RegisterExpressionFactory fRegisterExpressionFactory = new RegisterExpressionFactory(); 
    final private SyncRegisterDataAccess fSyncRegisterDataAccess; 
    private final IFormattedValuePreferenceStore fFormattedPrefStore;

    public RegisterVMNode(IFormattedValuePreferenceStore prefStore, AbstractDMVMProvider provider, DsfSession session, SyncRegisterDataAccess syncDataAccess) {
        super(provider, session, IRegisters.IRegisterDMContext.class);
        fSyncRegisterDataAccess = syncDataAccess;
        fFormattedPrefStore = prefStore;
    }

    protected SyncRegisterDataAccess getSyncRegisterDataAccess() {
        return fSyncRegisterDataAccess;
    }

    public IFormattedValuePreferenceStore getPreferenceStore() {
        return fFormattedPrefStore;
    }
    
    /**
     *  Private data access routine which performs the extra level of data access needed to
     *  get the formatted data value for a specific register.
     */
    private void updateFormattedRegisterValue(final ILabelUpdate update, final int labelIndex, final IRegisterDMContext dmc)
    {
        final IRegisters regService = getServicesTracker().getService(IRegisters.class);
        
        if ( regService == null ) {
        	handleFailedUpdate(update);
            return;
        }
        
        /*
         *  First select the format to be used. This involves checking so see that the preference
         *  page format is supported by the register service. If the format is not supported then 
         *  we will pick the first available format.
         */
        
        final IPresentationContext context  = update.getPresentationContext();
        final String preferencePageFormatId = fFormattedPrefStore.getCurrentNumericFormat(context) ;
            
        regService.getAvailableFormats(
            dmc,
            new DataRequestMonitor<String[]>(getSession().getExecutor(), null) {
                @Override
                public void handleCompleted() {
                    if (!isSuccess()) {
                        handleFailedUpdate(update);
                        return;
                    }
                    
                    /*
                     *  See if the desired format is supported.
                     */
                    String[] formatIds = getData();
                    String   finalFormatId = IFormattedValues.NATURAL_FORMAT;
                    boolean  requestedFormatIsSupported = false;
                    
                    for ( String fId : formatIds ) {
                        if ( preferencePageFormatId.equals(fId) ) {
                            /*
                             *  Desired format is supported.
                             */
                            finalFormatId = preferencePageFormatId;
                            requestedFormatIsSupported = true;
                            break;
                        }
                    }
                    
                    if ( ! requestedFormatIsSupported ) {
                        /*
                         *  Desired format is not supported. If there are any formats supported
                         *  then use the first available.
                         */
                        if ( formatIds.length != 0 ) {
                            finalFormatId = formatIds[0];
                        }
                        else {
                            /*
                             *  Register service does not support any format.
                             */
                            handleFailedUpdate(update);
                            return;
                        }
                    }
                    
                    /*
                     *  Format has been validated. Get the formatted value.
                     */
                    final FormattedValueDMContext valueDmc = regService.getFormattedValueContext(dmc, finalFormatId);
                    
                    getDMVMProvider().getModelData(
                        RegisterVMNode.this, update, regService, valueDmc,
	                    new DataRequestMonitor<FormattedValueDMData>(getSession().getExecutor(), null) {
	                        @Override
	                        public void handleCompleted() {
	                            if (!isSuccess()) {
	                            	if (getStatus().getCode() == IDsfStatusConstants.INVALID_STATE) {
	                                    update.setLabel("...", labelIndex); //$NON-NLS-1$
	                                } else {
	                                    update.setLabel("Error: " + getStatus().getMessage(), labelIndex); //$NON-NLS-1$
	                                }
	                                update.setFontData(JFaceResources.getFontDescriptor(IInternalDebugUIConstants.VARIABLE_TEXT_FONT).getFontData()[0], labelIndex);
	                                update.done();
	                                return;
	                            }
	                            /*
	                             *  Fill the label/column with the properly formatted data value.
	                             */
	                            update.setLabel(getData().getFormattedValue(), labelIndex);
	                                
	                            // color based on change history
	                            
	                            FormattedValueDMData oldData = (FormattedValueDMData) getDMVMProvider().getArchivedModelData(
	                                RegisterVMNode.this, update, valueDmc);
	                            if(oldData != null && !oldData.getFormattedValue().equals(getData().getFormattedValue())) {
	                                update.setBackground(
	                                    DebugUIPlugin.getPreferenceColor(IInternalDebugUIConstants.PREF_CHANGED_VALUE_BACKGROUND).getRGB(), labelIndex);
	                            }
	                            update.setFontData(JFaceResources.getFontDescriptor(IInternalDebugUIConstants.VARIABLE_TEXT_FONT).getFontData()[0], labelIndex);
	                            update.done();
	                        }
	                    }, 
	                    getSession().getExecutor()
                    );
                }
            }
        );
    }
    
    /*
     * (non-Javadoc)
     * @see org.eclipse.debug.internal.ui.viewers.model.provisional.IElementLabelProvider#update(org.eclipse.debug.internal.ui.viewers.model.provisional.ILabelUpdate[])
     */
    
    public void update(final ILabelUpdate[] updates) {
        try {
            getSession().getExecutor().execute(new DsfRunnable() {
                public void run() {
                    updateLabelInSessionThread(updates);
                }});
        } catch (RejectedExecutionException e) {
            for (ILabelUpdate update : updates) {
                handleFailedUpdate(update);
            }
        }
    }

    /*
     *  Updates the labels which are controlled by the column being requested.
     */
    protected void updateLabelInSessionThread(ILabelUpdate[] updates) {
        for (final ILabelUpdate update : updates) {
        	
            final IRegisterDMContext dmc = findDmcInPath(update.getViewerInput(), update.getElementPath(), IRegisters.IRegisterDMContext.class);
            if ( dmc == null ) {
            	handleFailedUpdate(update);
                continue;
            }
            
            IRegisters regService = getServicesTracker().getService(IRegisters.class);
            if ( regService == null ) {
            	handleFailedUpdate(update);
                continue;
            }
            
            
            getDMVMProvider().getModelData(
                this, 
                update, 
                regService,
        		dmc,             
        		new DataRequestMonitor<IRegisterDMData>(getSession().getExecutor(), null) { 
                @Override
                protected void handleCompleted() {
                    /*
                     * Check that the request was evaluated and data is still
                     * valid.  The request could fail if the state of the 
                     * service changed during the request, but the view model
                     * has not been updated yet.
                     */ 
                    if (!isSuccess()) {
                        assert getStatus().isOK() || 
                               getStatus().getCode() != IDsfStatusConstants.INTERNAL_ERROR || 
                               getStatus().getCode() != IDsfStatusConstants.NOT_SUPPORTED;
                        /*
                         *  Instead of just failing this outright we are going to attempt to do more here.
                         *  Failing it outright causes the view to display ... for all columns in the line
                         *  and this is uninformative about what is happening. We may be trying to show  a
                         *  register whos retrieval has been cancelled by the lower level. Perhaps because
                         *  we are stepping extremely fast and state changes cause the register service to
                         *  return these requests without ever sending them to the debug engine.
                         *  
                         */
                        String[] localColumns = update.getPresentationContext().getColumns();
                        if (localColumns == null)
                            localColumns = new String[] { IDebugVMConstants.COLUMN_ID__NAME };
                        
                        for (int idx = 0; idx < localColumns.length; idx++) {
                            if (IDebugVMConstants.COLUMN_ID__NAME.equals(localColumns[idx])) {
                            	/*
                            	 *  This used to be easy in that the DMC contained the name.  Which allowed us
                            	 *  to display the register name and an error message across from it. Now that
                            	 *  name must come from the data and we could not retrieve the data we do  not
                            	 *  have anything intelligent to show here. I think this is going to look very
                            	 *  ugly and will need to be worked on. We know the service has the name  with
                            	 *  it, it is just the dynamic part which cannot be obtained ( as explained in
                            	 *  comments above ). 
                            	 */
                                update.setLabel("Unknown name", idx); //$NON-NLS-1$
                                update.setImageDescriptor(DebugPluginImages.getImageDescriptor(IDebugUIConstants.IMG_OBJS_REGISTER), idx);
                            } else if (IDebugVMConstants.COLUMN_ID__TYPE.equals(localColumns[idx])) {
                                update.setLabel("", idx); //$NON-NLS-1$
                            } else if (IDebugVMConstants.COLUMN_ID__VALUE.equals(localColumns[idx])) {
                                if (getStatus().getCode() == IDsfStatusConstants.INVALID_STATE) {
                                    update.setLabel("...", idx); //$NON-NLS-1$
                                } else {
                                    update.setLabel("Error: " + getStatus().getMessage(), idx); //$NON-NLS-1$
                                }
                            } else if (IDebugVMConstants.COLUMN_ID__DESCRIPTION.equals(localColumns[idx])) {
                                update.setLabel("...", idx); //$NON-NLS-1$
                            } else if (IDebugVMConstants.COLUMN_ID__EXPRESSION.equals(localColumns[idx])) {
                                update.setLabel("", idx); //$NON-NLS-1$
                            }
                            
                            update.setFontData(JFaceResources.getFontDescriptor(IInternalDebugUIConstants.VARIABLE_TEXT_FONT).getFontData()[0], idx);
                        }
                        
                        update.done();
                        return;
                    }
                    
                    /*
                     * If columns are configured, extract the selected values for each
                     * understood column. First we fill all of those columns which can
                     * be filled without the extra data mining. We also note if we  do
                     * have to datamine. Any columns need to set the processing flag
                     * so we know we have further work to do. If there are more columns
                     * which need data extraction they need to be added in both "for"
                     * loops.
                     */
                    String[] localColumns = update.getColumnIds();
                    if (localColumns == null) localColumns = new String[] { IDebugVMConstants.COLUMN_ID__NAME }; 
                    
                    boolean weAreExtractingFormattedData = false;
                    
                    for (int idx = 0; idx < localColumns.length; idx++) {
                    	update.setFontData(JFaceResources.getFontDescriptor(IInternalDebugUIConstants.VARIABLE_TEXT_FONT).getFontData()[0], idx);
                        if (IDebugVMConstants.COLUMN_ID__NAME.equals(localColumns[idx])) {
                            update.setLabel(getData().getName(), idx);
                            update.setImageDescriptor(DebugPluginImages.getImageDescriptor(IDebugUIConstants.IMG_OBJS_REGISTER), idx);
                        } else if (IDebugVMConstants.COLUMN_ID__VALUE.equals(localColumns[idx])) {
                            weAreExtractingFormattedData = true;
                        } else if (IDebugVMConstants.COLUMN_ID__TYPE.equals(localColumns[idx])) {
                            IRegisterDMData data = getData();
                            String typeStr      = "Unsigned"; //$NON-NLS-1$
                            String ReadAttrStr  = "ReadNone"; //$NON-NLS-1$
                            String WriteAddrStr = "WriteNone"; //$NON-NLS-1$
                            
                            if ( data.isFloat() ) { typeStr = "Floating Point"; } //$NON-NLS-1$ 
                            
                                 if ( data.isReadOnce() ) { ReadAttrStr = "ReadOnce"; } //$NON-NLS-1$
                            else if ( data.isReadable() ) { ReadAttrStr = "Readable"; } //$NON-NLS-1$
                            
                                 if ( data.isReadOnce() ) { WriteAddrStr = "WriteOnce"; } //$NON-NLS-1$
                            else if ( data.isReadable() ) { WriteAddrStr = "Writeable"; } //$NON-NLS-1$
                            
                            typeStr += " - " + ReadAttrStr + "/" + WriteAddrStr; //$NON-NLS-1$ //$NON-NLS-2$
                            update.setLabel(typeStr, idx);
                        } else if (IDebugVMConstants.COLUMN_ID__DESCRIPTION.equals(localColumns[idx])) {
                            update.setLabel(getData().getDescription(), idx);
                        } else if (IDebugVMConstants.COLUMN_ID__EXPRESSION.equals(localColumns[idx])) {
                            IVMContext vmc = (IVMContext)update.getElement();
                            IExpression expression = (IExpression)vmc.getAdapter(IExpression.class);
                            if (expression != null) {
                                update.setLabel(expression.getExpressionText(), idx);
                            } else {
                                update.setLabel(getData().getName(), idx);
                            } 
                        }
                    }
                    
                    if ( ! weAreExtractingFormattedData ) {
                        update.done();
                    } else {
                        for (int idx = 0; idx < localColumns.length; idx++) {
                            if (IDebugVMConstants.COLUMN_ID__VALUE.equals(localColumns[idx])) {
                                updateFormattedRegisterValue(update, idx, dmc);
                            }
                        }
                    }
                }
            },
            getSession().getExecutor());
        }
    }

    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.ui.viewmodel.datamodel.AbstractDMVMNode#updateElementsInSessionThread(org.eclipse.debug.internal.ui.viewers.model.provisional.IChildrenUpdate)
     */
    @Override
    protected void updateElementsInSessionThread(final IChildrenUpdate update) {
        
        IRegisters regService = getServicesTracker().getService(IRegisters.class);
        
        if ( regService == null ) {
        	handleFailedUpdate(update);
            return;
        }
        
        regService.getRegisters(
            createCompositeDMVMContext(update),
            new DataRequestMonitor<IRegisterDMContext[]>(getSession().getExecutor(), null) { 
                @Override
                public void handleCompleted() {
                    if (!isSuccess()) {
                        handleFailedUpdate(update);
                        return;
                    }
                    fillUpdateWithVMCs(update, getData());
                    update.done();
                }
            });            
    }
    
    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.ui.viewmodel.datamodel.AbstractDMVMNode#createVMContext(org.eclipse.dd.dsf.datamodel.IDMContext)
     */
    @Override
    protected IDMVMContext createVMContext(IDMContext dmc) {
        return new RegisterVMC(dmc);
    }
    
    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.ui.viewmodel.IVMNode#getDeltaFlags(java.lang.Object)
     */
    public int getDeltaFlags(Object e) {
        if (e instanceof IRunControl.ISuspendedDMEvent) {
            return IModelDelta.CONTENT;
        }
        
        if (e instanceof IRegisters.IRegistersChangedDMEvent) {
            return IModelDelta.CONTENT;
        }
        
        if (e instanceof IMemory.IMemoryChangedEvent) {
            return IModelDelta.CONTENT;
        }
        
        if (e instanceof IRegisters.IRegisterChangedDMEvent) {
            /*
             *  Logically one would think that STATE should be specified here.  But we specify  CONTENT
             *  as well so that if there are sub-registers ( BIT FIELDS ) they will be forced to update
             *  and show new values when the total register changes.
             */
            return IModelDelta.CONTENT;
        }
        
        if (e instanceof PropertyChangeEvent && 
            ((PropertyChangeEvent)e).getProperty() == IDebugVMConstants.CURRENT_FORMAT_STORAGE) 
        {
            return IModelDelta.CONTENT;            
        }
        return IModelDelta.NO_CHANGE;
    }

    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.ui.viewmodel.IVMNode#buildDelta(java.lang.Object, org.eclipse.dd.dsf.ui.viewmodel.VMDelta, int, org.eclipse.dd.dsf.concurrent.RequestMonitor)
     */
    public void buildDelta(Object e, VMDelta parentDelta, int nodeOffset, RequestMonitor rm) {
        if (e instanceof IRunControl.ISuspendedDMEvent) {
            // Create a delta that the whole register group has changed.
            parentDelta.setFlags(parentDelta.getFlags() | IModelDelta.CONTENT);
        } 
        
        if (e instanceof IRegisters.IRegistersChangedDMEvent) {
            parentDelta.setFlags(parentDelta.getFlags() | IModelDelta.CONTENT);;
        } 
        
        if (e instanceof IMemory.IMemoryChangedEvent) {
            // Mark the parent delta indicating that elements were added and/or removed.
            parentDelta.setFlags(parentDelta.getFlags() | IModelDelta.CONTENT);
        }

        if (e instanceof IRegisters.IRegisterChangedDMEvent) {
            parentDelta.addNode( createVMContext(((IRegisterChangedDMEvent)e).getDMContext()), IModelDelta.CONTENT | IModelDelta.STATE );
        } 

        if (e instanceof PropertyChangeEvent && 
            ((PropertyChangeEvent)e).getProperty() == IDebugVMConstants.CURRENT_FORMAT_STORAGE) 
        {
            parentDelta.setFlags(parentDelta.getFlags() | IModelDelta.CONTENT);
        }
        
        rm.done();
    }

    /**
     * Expected format: GRP( GroupName ).REG( RegisterName )
     *              or: $RegisterName
     */
 
    public boolean canParseExpression(IExpression expression) {
        return parseExpressionForRegisterName(expression.getExpressionText()) != null;
    }
    
    private String parseExpressionForRegisterName(String expression) {
    	if (expression.startsWith("GRP(")) { //$NON-NLS-1$
    		/*
    		 * Get the group portion.
    		 */
    		int startIdx = "GRP(".length(); //$NON-NLS-1$
            int endIdx = expression.indexOf(')', startIdx);
            String remaining = expression.substring(endIdx+1);
            if ( ! remaining.startsWith(".REG(") ) { //$NON-NLS-1$
                return null;
            }
            
            /*
             * Get the register portion.
             */
            startIdx = ".REG(".length(); //$NON-NLS-1$
            endIdx = remaining.indexOf(')', startIdx);
            String regName = remaining.substring(startIdx,endIdx);
            return regName.trim();
        }
    	else if ( expression.startsWith("$") ) { //$NON-NLS-1$
    		/*
    		 * At this point I am leaving this code here to represent the register case. To do this
    		 * correctly would be to use the findRegister function and upgrade the register service
    		 * to deal with registers that  do not have a specified group parent context.  I do not
    		 * have the time for this right now.  So by saying we do not handle this the Expression
    		 * VM node will take it and pass it to the debug engine  as a generic expression.  Most
    		 * debug engines ( GDB included )  have an inherent knowledge  of the core registers as
    		 * part of their expression evaluation  and will respond with a flat value for the reg.
    		 * This is not totally complete in that you should be able to express  a register which
    		 * has bit fields for example and the bit fields should be expandable in the expression
    		 * view. With this method it will just appear to have a single value and no sub-fields.
    		 * I will file a defect/enhancement  for this to mark it.  This comment will act as the
    		 * place-holder for the future work.
    		 */
    		return null;
    	}
    	
        return null;
    }
    
    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.expression.AbstractExpressionVMNode#testElementForExpression(java.lang.Object, org.eclipse.debug.core.model.IExpression, org.eclipse.dd.dsf.concurrent.DataRequestMonitor)
     */
    @Override
    protected void testElementForExpression(Object element, IExpression expression, final DataRequestMonitor<Boolean> rm) {
        if (!(element instanceof IDMVMContext)) {
            rm.setStatus(new Status(IStatus.ERROR, DsfDebugUIPlugin.PLUGIN_ID, IDsfStatusConstants.INVALID_HANDLE, "Invalid context", null)); //$NON-NLS-1$
            rm.done();
            return;
        }
        final IRegisterDMContext dmc = DMContexts.getAncestorOfType(((IDMVMContext)element).getDMContext(), IRegisterDMContext.class);
        if (dmc == null) {
            rm.setStatus(new Status(IStatus.ERROR, DsfDebugUIPlugin.PLUGIN_ID, IDsfStatusConstants.INVALID_HANDLE, "Invalid context", null)); //$NON-NLS-1$
            rm.done();
            return;
        }
        
        final String regName = parseExpressionForRegisterName(expression.getExpressionText());
        try {
            getSession().getExecutor().execute(new DsfRunnable() {
                public void run() {
                    IRegisters registersService = getServicesTracker().getService(IRegisters.class);
                    if (registersService != null) {
                        registersService.getRegisterData(
                            dmc, 
                            new DataRequestMonitor<IRegisterDMData>(ImmediateExecutor.getInstance(), rm) {
                                @Override
                                protected void handleSuccess() {
                                    rm.setData( getData().getName().equals(regName) );
                                    rm.done();
                                }
                            });
                    } else {
                        rm.setStatus(new Status(IStatus.WARNING, DsfDebugUIPlugin.PLUGIN_ID, IDsfStatusConstants.INVALID_STATE, "Register service not available", null)); //$NON-NLS-1$                        
                        rm.done();
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            rm.setStatus(new Status(IStatus.WARNING, DsfDebugUIPlugin.PLUGIN_ID, IDsfStatusConstants.INVALID_STATE, "DSF session shut down", null)); //$NON-NLS-1$
            rm.done();
        }
    }

    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.expression.AbstractExpressionVMNode#associateExpression(java.lang.Object, org.eclipse.debug.core.model.IExpression)
     */
    @Override
    protected void associateExpression(Object element, IExpression expression) {
        if (element instanceof RegisterVMC) {
            ((RegisterVMC)element).setExpression(expression);
        }
    }
    
    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.expression.IExpressionVMNode#getDeltaFlagsForExpression(org.eclipse.debug.core.model.IExpression, java.lang.Object)
     */
    public int getDeltaFlagsForExpression(IExpression expression, Object event) {
        if (event instanceof IRunControl.ISuspendedDMEvent) {
            return IModelDelta.CONTENT;
        }
        if (event instanceof PropertyChangeEvent && 
            ((PropertyChangeEvent)event).getProperty() == IDebugVMConstants.CURRENT_FORMAT_STORAGE) 
        {
            return IModelDelta.CONTENT;            
        }

        if (event instanceof IMemory.IMemoryChangedEvent) {
        	return IModelDelta.CONTENT;
        }
        
        return IModelDelta.NO_CHANGE;
    }
    
    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.expression.IExpressionVMNode#buildDeltaForExpression(org.eclipse.debug.core.model.IExpression, int, java.lang.Object, org.eclipse.dd.dsf.ui.viewmodel.VMDelta, org.eclipse.jface.viewers.TreePath, org.eclipse.dd.dsf.concurrent.RequestMonitor)
     */
    public void buildDeltaForExpression(IExpression expression, int elementIdx, Object event, VMDelta parentDelta, 
        TreePath path, RequestMonitor rm) 
    {
        if (event instanceof IRunControl.ISuspendedDMEvent) {
            // Mark the parent delta indicating that elements were added and/or removed.
            parentDelta.setFlags(parentDelta.getFlags() | IModelDelta.CONTENT);
        } 
        
        if (event instanceof IMemory.IMemoryChangedEvent) {
        	parentDelta.setFlags(parentDelta.getFlags() | IModelDelta.CONTENT);
        }
        
        rm.done();
    }

    /*
     * (non-Javadoc)
     * @see org.eclipse.dd.dsf.debug.internal.provisional.ui.viewmodel.expression.IExpressionVMNode#buildDeltaForExpressionElement(java.lang.Object, int, java.lang.Object, org.eclipse.dd.dsf.ui.viewmodel.VMDelta, org.eclipse.dd.dsf.concurrent.RequestMonitor)
     */
    public void buildDeltaForExpressionElement(Object element, int elementIdx, Object event, VMDelta parentDelta, final RequestMonitor rm) 
    {
        if (event instanceof IRegisters.IRegisterChangedDMEvent) {
            parentDelta.addNode(element, IModelDelta.STATE);
        } 
        
        if (event instanceof IRegisters.IRegistersChangedDMEvent) {
            parentDelta.addNode(element, IModelDelta.STATE);
        } 
        
        if (event instanceof IMemory.IMemoryChangedEvent) {
            parentDelta.addNode(element, IModelDelta.CONTENT);
        }
        
        if (event instanceof PropertyChangeEvent && 
            ((PropertyChangeEvent)event).getProperty() == IDebugVMConstants.CURRENT_FORMAT_STORAGE) 
        {
            parentDelta.addNode(element, IModelDelta.CONTENT);
        }

        rm.done();
    }

    /*
     * (non-Javadoc)
     * @see org.eclipse.debug.internal.ui.viewers.model.provisional.IElementEditor#getCellEditor(org.eclipse.debug.internal.ui.viewers.model.provisional.IPresentationContext, java.lang.String, java.lang.Object, org.eclipse.swt.widgets.Composite)
     */
    public CellEditor getCellEditor(IPresentationContext context, String columnId, Object element, Composite parent) {
        if (IDebugVMConstants.COLUMN_ID__EXPRESSION.equals(columnId)) {
            return new TextCellEditor(parent);
        } 
        else if (IDebugVMConstants.COLUMN_ID__VALUE.equals(columnId)) {
          /*
           *   See if the register is writable and if so we will created a
           *   cell editor for it.
           */
          IRegisterDMData regData = fSyncRegisterDataAccess.readRegister(element);

          if ( regData != null && regData.isWriteable() ) {
              return new TextCellEditor(parent);
          }
      }
      return null;
    }
    
    /*
     * (non-Javadoc)
     * @see org.eclipse.debug.internal.ui.viewers.model.provisional.IElementEditor#getCellModifier(org.eclipse.debug.internal.ui.viewers.model.provisional.IPresentationContext, java.lang.Object)
     */
    public ICellModifier getCellModifier(IPresentationContext context, Object element) {
        return new RegisterCellModifier( 
            getDMVMProvider(), fFormattedPrefStore, fSyncRegisterDataAccess );
    }
}
