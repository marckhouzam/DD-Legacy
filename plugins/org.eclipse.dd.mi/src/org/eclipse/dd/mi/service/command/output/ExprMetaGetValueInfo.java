/*******************************************************************************
 * Copyright (c) 2007 Ericsson and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Ericsson           - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.mi.service.command.output;

import org.eclipse.dd.dsf.debug.service.command.ICommand;
import org.eclipse.dd.dsf.debug.service.command.ICommandResult;

public class ExprMetaGetValueInfo implements ICommandResult {
	    
		private final String value;

	    public ExprMetaGetValueInfo(String v) {
	    	value = v;
	    }
	    
	    public String getValue() { return value; }
		
		public <V extends ICommandResult> V getSubsetResult(ICommand<V> command) {
			return null;
		}
	}