/*******************************************************************************
 * Copyright (c) 2000, 2007 QNX Software Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     QNX Software Systems - Initial API and implementation
 *     Ericsson				- Modified for handling of contexts
 *******************************************************************************/

package org.eclipse.dd.mi.service.command.commands;

import org.eclipse.dd.dsf.debug.service.command.ICommandControlService.ICommandControlDMContext;
import org.eclipse.dd.mi.service.command.MIControlDMContext;
import org.eclipse.dd.mi.service.command.output.MIInfo;


/**	
 *   -file-symbol-file [FILE]
 *   
 *   Read symbol table info from the specified file argument. When used without 
 *   arguments, clears GDB's symbol table info. No output is produced, except 
 *   for a completion notification.
 */
public class MIFileSymbolFile extends MICommand<MIInfo>
{
    /**
     * @since 1.1
     */
    public MIFileSymbolFile(ICommandControlDMContext dmc, String file) {
        super(dmc, "-file-symbol-file", null, new String[] {file}); //$NON-NLS-1$
    }

    @Deprecated
    public MIFileSymbolFile(MIControlDMContext dmc, String file) {
        this ((ICommandControlDMContext)dmc, file);
    }
    
    /**
     * @since 1.1
     */
    public MIFileSymbolFile(ICommandControlDMContext dmc) {
        super(dmc, "-file-symbol-file"); //$NON-NLS-1$
    }

    @Deprecated
    public MIFileSymbolFile(MIControlDMContext dmc) {
        this ((ICommandControlDMContext)dmc);
    }
}
