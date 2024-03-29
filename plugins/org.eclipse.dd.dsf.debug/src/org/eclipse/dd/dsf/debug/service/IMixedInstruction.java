/*******************************************************************************
 * Copyright (c) 2008 Ericsson and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Ericsson - initial API and implementation
 *******************************************************************************/

package org.eclipse.dd.dsf.debug.service;

/**
 * Represents the assembly instruction(s) corresponding to a source line
 */
public interface IMixedInstruction {

    /**
     * @return the file name
     */
    String getFileName();
    
    /**
     * @return the line Number.
     */
    int getLineNumber();
    
    /**
     * @return the array of instruction.
     */
    IInstruction[] getInstructions();

}
