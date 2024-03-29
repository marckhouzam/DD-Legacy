/*******************************************************************************
 * Copyright (c) 2007, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.dsf.ui.viewmodel.properties;

import java.text.MessageFormat;
import java.util.Map;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.dd.dsf.internal.ui.DsfUIPlugin;
import org.eclipse.debug.internal.ui.viewers.model.provisional.ILabelUpdate;

/**
 * The text attribute of a label.  It uses a message format string in order to 
 * compose the text string.  The parameter names determine the array of objects
 * given to the message format. 
 * 
 * @see MessageFormat#format(Object[], StringBuffer, java.text.FieldPosition)
 * @see LabelAttribute
 * @see LabelColumnInfo
 * @see PropertyBasedLabelProvider
 */
@SuppressWarnings("restriction")
public class LabelText extends LabelAttribute {
    
    public static final MessageFormat DEFAULT_MESSAGE = new MessageFormat(MessagesForProperties.DefaultLabelMessage_label);
   
    /**
     * Message format used to generate the label text.
     * 
     */
    private MessageFormat fMessageFormat;
    
    /**
     * The property names needed for the message format.  The property values 
     * corresponding to these names are given the the {@link MessageFormat#format(Object[], StringBuffer, java.text.FieldPosition)}
     * method.  
     */
    private String[] fPropertyNames;

    public LabelText() {
        this(DEFAULT_MESSAGE, EMPTY_PROPERTY_NAMES_ARRAY);
    }

    public LabelText(MessageFormat format, String[] propertyNames) {
        fMessageFormat = format;
        fPropertyNames = propertyNames;
    }
    
    @Override
    public String[] getPropertyNames() {
        return fPropertyNames;
    }
    
    public MessageFormat getMessageFormat() {
        return fMessageFormat;
    }

    public void setMessageFormat(MessageFormat messageFormat) {
        fMessageFormat = messageFormat;
        fireAttributeChanged();
    }

    @Override
    public void updateAttribute(ILabelUpdate update, int columnIndex, Map<String, Object> properties) {
        String[] propertyNames = getPropertyNames();
        Object[] propertyValues = new Object[propertyNames.length];
        for (int i = 0; i < propertyNames.length; i++) {
            propertyValues[i] = properties.get(propertyNames[i]); 
        }
        
        try {
            update.setLabel(getMessageFormat().format(propertyValues, new StringBuffer(), null).toString(), columnIndex);
        } catch (IllegalArgumentException e) {
            update.setStatus(new Status(IStatus.ERROR, DsfUIPlugin.PLUGIN_ID, 0, "Failed formatting a message for column " + columnIndex + ", for update " + update, e)); //$NON-NLS-1$ //$NON-NLS-2$
        }
    }
}