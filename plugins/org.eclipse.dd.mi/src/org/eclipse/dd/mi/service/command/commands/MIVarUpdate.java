/*******************************************************************************
 * Copyright (c) 2000, 2006 QNX Software Systems and others.
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

import org.eclipse.dd.dsf.debug.service.command.ICommandControlService.ICommandControlDMContext;
import org.eclipse.dd.mi.service.command.MIControlDMContext;
import org.eclipse.dd.mi.service.command.output.MIOutput;
import org.eclipse.dd.mi.service.command.output.MIVarUpdateInfo;

/**
 * 
 *     -var-update [print-values] {NAME | "*"}
 *
 *  Update the value of the variable object NAME by evaluating its
 *  expression after fetching all the new values from memory or registers.
 *  A `*' causes all existing variable objects to be updated.
  * If print-values has a value for of 0 or --no-values, print only the names of the variables; 
  * if print-values is 1 or --all-values, also print their values; 
  * if it is 2 or --simple-values print the name and value for simple data types and just 
  * the name for arrays, structures and unions. 
 */
public class MIVarUpdate extends MICommand<MIVarUpdateInfo> {

	/**
     * @since 1.1
     */
	public MIVarUpdate(ICommandControlDMContext dmc, String name) {
		super(dmc, "-var-update", new String[] { "1", name }); //$NON-NLS-1$//$NON-NLS-2$
	}
    
    @Deprecated
    public MIVarUpdate(MIControlDMContext ctx, String name) {
        this ((ICommandControlDMContext)ctx, name);
    }
	
    @Override
    public MIVarUpdateInfo getResult(MIOutput out) {
        return new MIVarUpdateInfo(out);
    }
}
