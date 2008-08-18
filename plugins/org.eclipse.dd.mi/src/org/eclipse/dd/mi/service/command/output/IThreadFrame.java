/*******************************************************************************
 * Copyright (c) 2008 Ericsson and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Ericsson - Initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.mi.service.command.output;

import java.math.BigInteger;

public interface IThreadFrame {
	int        getStackLevel();
	BigInteger getAddress();
	String     getFucntion();
	Object[]   getArgs();
	String     getFileName();
	String     getFullName();
	int        getLineNumber();
}