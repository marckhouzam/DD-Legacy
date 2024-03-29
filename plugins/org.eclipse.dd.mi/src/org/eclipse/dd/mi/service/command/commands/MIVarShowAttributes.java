/*******************************************************************************
 * Copyright (c) 2000, 2006 QNX Software Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     QNX Software Systems - Initial API and implementation
 *******************************************************************************/

package org.eclipse.dd.mi.service.command.commands;

import org.eclipse.dd.dsf.debug.service.command.ICommandControlService.ICommandControlDMContext;
import org.eclipse.dd.mi.service.command.MIControlDMContext;
import org.eclipse.dd.mi.service.command.output.MIOutput;
import org.eclipse.dd.mi.service.command.output.MIVarShowAttributesInfo;

/**
 * 
 *    -var-show-attributes NAME
 *
 *  List attributes of the specified variable object NAME:
 *
 *    status=ATTR [ ( ,ATTR )* ]
 *
 * where ATTR is `{ { editable | noneditable } | TBD }'.
 * 
 */
//DsfMIVarShowAttributesInfo

public class MIVarShowAttributes extends MICommand<MIVarShowAttributesInfo> 
{
	/**
     * @since 1.1
     */
	public MIVarShowAttributes(ICommandControlDMContext ctx, String name) {
		super(ctx, "-var-show-attributes", new String[]{name}); //$NON-NLS-1$
	}
    
    @Deprecated
	public MIVarShowAttributes(MIControlDMContext ctx, String name) {
	    this ((ICommandControlDMContext)ctx, name);
	}
	
    @Override
    public MIVarShowAttributesInfo getResult(MIOutput out) {
        return new MIVarShowAttributesInfo(out);
    }
}
