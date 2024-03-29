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
 *     Ericsson				- Modified for handling of frame contexts
 *******************************************************************************/

package org.eclipse.dd.mi.service.command.commands;

import org.eclipse.dd.dsf.debug.service.IFormattedValues;
import org.eclipse.dd.dsf.debug.service.command.ICommandControlService.ICommandControlDMContext;
import org.eclipse.dd.mi.service.command.MIControlDMContext;
import org.eclipse.dd.mi.service.command.output.MIOutput;
import org.eclipse.dd.mi.service.command.output.MIVarSetFormatInfo;

/**
 * 
 *    -var-set-format NAME FORMAT-SPEC
 *
 *  Sets the output format for the value of the object NAME to be
 * FORMAT-SPEC.
 *
 *  The syntax for the FORMAT-SPEC is as follows:
 *
 *     FORMAT-SPEC ==>
 *     {binary | decimal | hexadecimal | octal | natural}
 * 
 */
public class MIVarSetFormat extends MICommand<MIVarSetFormatInfo> 
{
    /**
     * @since 1.1
     */
    public MIVarSetFormat(ICommandControlDMContext ctx, String name, String fmt) {
        super(ctx, "-var-set-format"); //$NON-NLS-1$
        setParameters(new String[]{name, getFormat(fmt)});
    }
    
    @Deprecated
    public MIVarSetFormat(MIControlDMContext ctx, String name, String fmt) {
        this ((ICommandControlDMContext)ctx, name, fmt);
    }
    
    private String getFormat(String fmt){
        String format = "natural"; //$NON-NLS-1$
        
        if (IFormattedValues.HEX_FORMAT.equals(fmt)) {
            format = "hexadecimal";  //$NON-NLS-1$
        } else if (IFormattedValues.BINARY_FORMAT.equals(fmt)) {
            format = "binary";  //$NON-NLS-1$
        } else if (IFormattedValues.OCTAL_FORMAT.equals(fmt)) {
            format = "octal";  //$NON-NLS-1$
        } else if (IFormattedValues.NATURAL_FORMAT.equals(fmt)) {
            format = "natural";  //$NON-NLS-1$
        } else if (IFormattedValues.DECIMAL_FORMAT.equals(fmt)) {
            format = "decimal";  //$NON-NLS-1$
        }  
        return format;
    }
    
    @Override
    public MIVarSetFormatInfo getResult(MIOutput out) {
        return new MIVarSetFormatInfo(out);
    }
}
