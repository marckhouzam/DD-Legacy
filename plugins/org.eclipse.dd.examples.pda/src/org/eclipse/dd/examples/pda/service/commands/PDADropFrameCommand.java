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

import org.eclipse.dd.examples.pda.service.PDAProgramDMContext;

/**
 * Returns from the current frame without executing the rest of instructions.  
 * 
 * <pre>
 *    C: drop
 *    R: ok
 *    E: resumed drop
 *    E: suspended drop
 * </pre>

 */
public class PDADropFrameCommand extends AbstractPDACommand<PDACommandResult> {

    public PDADropFrameCommand(PDAProgramDMContext context) {
        super(context, "drop");
    }
    
    @Override
    public PDACommandResult createResult(String resultText) {
        return new PDACommandResult(resultText);
    }
}