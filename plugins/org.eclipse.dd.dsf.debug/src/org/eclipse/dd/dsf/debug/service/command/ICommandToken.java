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
package org.eclipse.dd.dsf.debug.service.command;

/**
 * Token returned by ICommandControl.queueCommand().  This token can be used
 * to uniquely identify a command when calling ICommandControl.removeCommand()
 * or when implementing the ICommandListener listener methods.
 */
public interface ICommandToken {
    /**
     * Returns the command that this was created for.
     */
    public ICommand<? extends ICommandResult> getCommand();
}