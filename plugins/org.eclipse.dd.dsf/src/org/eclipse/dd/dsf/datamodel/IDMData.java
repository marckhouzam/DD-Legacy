/*******************************************************************************
 * Copyright (c) 2006, 2007 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.dsf.datamodel;

import org.eclipse.dd.dsf.concurrent.Immutable;

/**
 * Marker interface for data corresponding to IDMContext, retrieved from a 
 * service.  These data objects are meant to be processed by clients on 
 * different threads, therefore they should be immutable.
 */
@Immutable
public interface IDMData {
}
