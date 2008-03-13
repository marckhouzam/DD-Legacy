/*******************************************************************************
 * Copyright (c) 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.examples.pda.service.commands;

import org.eclipse.dd.dsf.concurrent.Immutable;
import org.eclipse.dd.examples.pda.service.PDAProgramDMContext;

/**
 * Sets what events cause the execution to stop.
 * 
 * <pre>
 *    C: eval {instruction}%20{parameter}|{instruction}%20{parameter}|...
 *    R: ok
 *    E: resume client
 *    E: evalresult result
 *    E: suspended eval
 * </pre>
 * 
 * Where event_name could be <code>unimpinstr</code> or <code>nosuchlabel</code>.  
 */
@Immutable
public class PDAEvalCommand extends AbstractPDACommand<PDACommandResult> {

    public PDAEvalCommand(PDAProgramDMContext context, String operation) {
        super(context, "eval " + operation);
    }
    
    @Override
    public PDACommandResult createResult(String resultText) {
        return new PDACommandResult(resultText);
    }
}
