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
import org.eclipse.dd.mi.service.command.output.MIVarInfoExpressionInfo;

/**
 * 
 *     -var-info-expression NAME
 *
 *  Returns what is represented by the variable object NAME:
 *
 *     lang=LANG-SPEC,exp=EXPRESSION
 *
 * where LANG-SPEC is `{"C" | "C++" | "Java"}'.
 * 
 */

//MIVarInfoExpression.java
public class MIVarInfoExpression extends MICommand<MIVarInfoExpressionInfo> 
{
	/**
     * @since 1.1
     */
	public MIVarInfoExpression(ICommandControlDMContext ctx, String name) {
		super(ctx, "-var-info-expression", new String[]{name}); //$NON-NLS-1$
	}
    
    @Deprecated
	public MIVarInfoExpression(MIControlDMContext ctx, String name) {
	    this ((ICommandControlDMContext)ctx, name);
	}
	
    @Override
    public MIVarInfoExpressionInfo getResult(MIOutput out) {
        return new MIVarInfoExpressionInfo(out);
    }
}
