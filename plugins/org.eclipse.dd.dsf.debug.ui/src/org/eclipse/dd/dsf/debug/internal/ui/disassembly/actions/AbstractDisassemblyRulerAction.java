/*******************************************************************************
 * Copyright (c) 2008 Wind River Systems, Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.dsf.debug.internal.ui.disassembly.actions;

import org.eclipse.dd.dsf.debug.internal.ui.disassembly.IDisassemblyPart;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IVerticalRulerInfo;

/**
 * Abstract implementation for disassembly vertical ruler actions.
 */
public abstract class AbstractDisassemblyRulerAction extends AbstractDisassemblyAction {

	private final IVerticalRulerInfo fRulerInfo;

	protected AbstractDisassemblyRulerAction(IDisassemblyPart disassemblyPart, IVerticalRulerInfo rulerInfo) {
		fDisassemblyPart= disassemblyPart;
		fRulerInfo= rulerInfo;
	}
	
	public final IVerticalRulerInfo getRulerInfo() {
		return fRulerInfo;
	}
	
	public final IDocument getDocument() {
		return getDisassemblyPart().getTextViewer().getDocument();
	}
	
	public final IAnnotationModel getAnnotationModel() {
		return getDisassemblyPart().getTextViewer().getAnnotationModel();
	}
}
