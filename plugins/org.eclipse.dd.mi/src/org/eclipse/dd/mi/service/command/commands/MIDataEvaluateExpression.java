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
 *******************************************************************************/

package org.eclipse.dd.mi.service.command.commands;

import org.eclipse.dd.dsf.debug.service.IExpressions.IExpressionDMContext;
import org.eclipse.dd.dsf.debug.service.IStack.IFrameDMContext;
import org.eclipse.dd.dsf.debug.service.command.ICommandControlService.ICommandControlDMContext;
import org.eclipse.dd.mi.service.IMIExecutionDMContext;
import org.eclipse.dd.mi.service.command.MIControlDMContext;
import org.eclipse.dd.mi.service.command.output.MIDataEvaluateExpressionInfo;
import org.eclipse.dd.mi.service.command.output.MIOutput;

/**
 * 
 *      -data-evaluate-expression EXPR
 *
 *   Evaluate EXPR as an expression.  The expression could contain an
 *inferior function call.  The function call will execute synchronously.
 *If the expression contains spaces, it must be enclosed in double quotes.
 *
 */
public class MIDataEvaluateExpression<V extends MIDataEvaluateExpressionInfo> extends MICommand<V> 
{
    /**
     * @since 1.1
     */
    public MIDataEvaluateExpression(ICommandControlDMContext ctx, String expr) {
        super(ctx, "-data-evaluate-expression", new String[]{expr}); //$NON-NLS-1$
    }

    @Deprecated
    public MIDataEvaluateExpression(MIControlDMContext ctx, String expr) {
        this ((ICommandControlDMContext)ctx, expr);
    }

    
    public MIDataEvaluateExpression(IMIExecutionDMContext execDmc, String expr) {
        super(execDmc, "-data-evaluate-expression", new String[]{expr}); //$NON-NLS-1$
    }

    public MIDataEvaluateExpression(IFrameDMContext frameDmc, String expr) {
        super(frameDmc, "-data-evaluate-expression", new String[]{expr}); //$NON-NLS-1$
    }

    public MIDataEvaluateExpression(IExpressionDMContext exprDmc) {
        super(exprDmc, "-data-evaluate-expression", new String[]{exprDmc.getExpression()}); //$NON-NLS-1$
    }

    @Override
    public MIDataEvaluateExpressionInfo getResult(MIOutput output) {
        return new MIDataEvaluateExpressionInfo(output);
    }
}
