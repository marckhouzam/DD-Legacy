/*******************************************************************************
 * Copyright (c) 2004, 2008 QNX Software Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * QNX Software Systems - Initial API and implementation
 * Nokia - Added support for AbsoluteSourceContainer( 159833 )
 * Wind River Systems - Adapted for use with DSF 
*******************************************************************************/
package org.eclipse.dd.dsf.debug.sourcelookup; 

import org.eclipse.cdt.debug.internal.core.sourcelookup.CSourceLookupDirector;
import org.eclipse.dd.dsf.service.DsfSession;
import org.eclipse.debug.core.sourcelookup.ISourceLookupParticipant;
 
/**
 * DSF source lookup director.
 */
public class DsfSourceLookupDirector extends CSourceLookupDirector {

	private final DsfSession fSession;
	
    public DsfSourceLookupDirector(DsfSession session) {
        fSession = session;
    }

	/* (non-Javadoc)
	 * @see org.eclipse.debug.core.sourcelookup.ISourceLookupDirector#initializeParticipants()
	 */
	@Override
    public void initializeParticipants() {
	    super.initializeParticipants();
		addParticipants( new ISourceLookupParticipant[]{ new DsfSourceLookupParticipant(fSession) } );
	}
	
}
