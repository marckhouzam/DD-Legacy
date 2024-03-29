/*******************************************************************************
 * Copyright (c) 2007, 2008 Wind River Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Wind River Systems - initial API and implementation
 *******************************************************************************/
package org.eclipse.dd.dsf.debug.internal.ui.disassembly.presentation;

import org.eclipse.cdt.core.model.CModelException;
import org.eclipse.cdt.core.model.CoreModel;
import org.eclipse.cdt.core.model.IAsmLanguage;
import org.eclipse.cdt.core.model.ICElement;
import org.eclipse.cdt.core.model.ICModel;
import org.eclipse.cdt.core.model.ICProject;
import org.eclipse.cdt.core.model.ILanguage;
import org.eclipse.cdt.core.model.ITranslationUnit;
import org.eclipse.cdt.internal.ui.editor.CDocumentProvider;
import org.eclipse.cdt.internal.ui.editor.ITranslationUnitEditorInput;
import org.eclipse.cdt.internal.ui.text.CCommentScanner;
import org.eclipse.cdt.internal.ui.text.CTextTools;
import org.eclipse.cdt.internal.ui.text.ICColorConstants;
import org.eclipse.cdt.internal.ui.text.IColorManager;
import org.eclipse.cdt.internal.ui.text.SimpleCSourceViewerConfiguration;
import org.eclipse.cdt.internal.ui.text.TokenStore;
import org.eclipse.cdt.internal.ui.util.EditorUtility;
import org.eclipse.cdt.ui.CUIPlugin;
import org.eclipse.cdt.ui.text.ICPartitions;
import org.eclipse.cdt.ui.text.ITokenStore;
import org.eclipse.cdt.ui.text.ITokenStoreFactory;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFileState;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IStorage;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.TextPresentation;
import org.eclipse.jface.text.presentation.PresentationReconciler;
import org.eclipse.jface.text.rules.ITokenScanner;
import org.eclipse.jface.text.rules.RuleBasedScanner;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * A presentation creator based on CDT syntax highlighting.
 */
@SuppressWarnings("restriction")
public class CSourcePresentationCreator extends PresentationReconciler implements ISourcePresentationCreator, IPropertyChangeListener {

	/**
	 *
	 */
	private final static class CustomCSourceViewerConfiguration extends SimpleCSourceViewerConfiguration {
		/**
		 * Comment for <code>fLanguage</code>
		 */
		private final ILanguage fLanguage;

		/**
		 * @param colorManager
		 * @param preferenceStore
		 * @param language
		 */
		private CustomCSourceViewerConfiguration(
				IColorManager colorManager, IPreferenceStore preferenceStore,
				ILanguage language) {
			super(colorManager, preferenceStore, null, ICPartitions.C_PARTITIONING, false);
			fLanguage = language;
		}

		public void dispose() {
		}

		/*
		 * @see org.eclipse.cdt.internal.ui.text.CSourceViewerConfiguration#getLanguage()
		 */
		@Override
		protected ILanguage getLanguage() {
			return fLanguage;
		}

		/**
		 * @param contentType
		 * @return
		 */
		public ITokenScanner getScannerForContentType(String contentType) {
			if (IDocument.DEFAULT_CONTENT_TYPE.equals(contentType)) {
				return getLanguage() != null ? getCodeScanner(getLanguage()) : null;
			} else if (ICPartitions.C_CHARACTER.equals(contentType)) {
				return getStringScanner();
			} else if (ICPartitions.C_STRING.equals(contentType)) {
				return getStringScanner();
			} else if (ICPartitions.C_SINGLE_LINE_COMMENT.equals(contentType)) {
				return getSinglelineCommentScanner();
			} else if (ICPartitions.C_SINGLE_LINE_DOC_COMMENT.equals(contentType)) {
				return getSinglelineDocCommentScanner(getProject());
			} else if (ICPartitions.C_MULTI_LINE_COMMENT.equals(contentType)) {
				return getMultilineCommentScanner();
			} else if (ICPartitions.C_MULTI_LINE_DOC_COMMENT.equals(contentType)) {
				return getMultilineDocCommentScanner(getProject());
			} else if (ICPartitions.C_PREPROCESSOR.equals(contentType)) {
				return getPreprocessorScanner(getLanguage());
			}
			return null;
		}

		private ITokenScanner getMultilineCommentScanner() {
			return new CCommentScanner(getTokenStoreFactory(),  ICColorConstants.C_SINGLE_LINE_COMMENT);
		}

		private ITokenScanner getSinglelineCommentScanner() {
			return new CCommentScanner(getTokenStoreFactory(),  ICColorConstants.C_MULTI_LINE_COMMENT);
		}

		/**
		 * Returns the ICProject associated with this CSourceViewerConfiguration, or null if
		 * no ICProject could be determined
		 * @return
		 */
		private ICProject internalGetCProject() {
			ITextEditor editor= getEditor();
			if (editor == null)
				return null;

			ICElement element= null;
			IEditorInput input= editor.getEditorInput();
			IDocumentProvider provider= editor.getDocumentProvider();
			if (provider instanceof CDocumentProvider) {
				CDocumentProvider cudp= (CDocumentProvider) provider;
				element= cudp.getWorkingCopy(input);
			}

			if (element == null)
				return null;

			return element.getCProject();
		}

		
	    /**
		 * @return the IProject associated with this CSourceViewerConfiguration, or null if
		 * no IProject could be determined
		 */
		private IProject getProject() {
			ICProject cproject= internalGetCProject();
			return cproject!=null ? cproject.getProject() :null;
		}

		private ITokenStoreFactory getTokenStoreFactory() {
			return new ITokenStoreFactory() {
				public ITokenStore createTokenStore(String[] propertyColorNames) {
					return new TokenStore(getColorManager(), fPreferenceStore, propertyColorNames);
				}
			};
		}

		/*
		 * @see org.eclipse.cdt.internal.ui.text.CSourceViewerConfiguration#getCodeScanner(org.eclipse.cdt.core.model.ILanguage)
		 */
		@Override
		protected RuleBasedScanner getCodeScanner(ILanguage language) {
			if (language instanceof IAsmLanguage) {
				return CUIPlugin.getDefault().getAsmTextTools().getCodeScanner();
			}
			return super.getCodeScanner(language);
		}
		
		/*
		 * @see org.eclipse.cdt.internal.ui.text.CSourceViewerConfiguration#getPreprocessorScanner(org.eclipse.cdt.core.model.ILanguage)
		 */
		@Override
		protected RuleBasedScanner getPreprocessorScanner(ILanguage language) {
			if (language instanceof IAsmLanguage) {
				return CUIPlugin.getDefault().getAsmTextTools().getPreprocessorScanner();
			}
			return super.getPreprocessorScanner(language);
		}
	}

	private ITextViewer fViewer;
	private ISourceTagProvider fSourceTagProvider;
	private SourceTagDamagerRepairer fDamagerRepairer;
	private ISourceTagListener fSourceTagListener;
	private TextPresentation fPresentation;
	private CustomCSourceViewerConfiguration fSourceViewerConfiguration;
	private IPreferenceStore fPreferenceStore;

	/**
	 * @param language
	 * @param storage
	 * @param textViewer
	 */
	public CSourcePresentationCreator(ILanguage language, IStorage storage, ITextViewer textViewer) {
		if (language != null) {
			fViewer= textViewer;
			fPreferenceStore= CUIPlugin.getDefault().getCombinedPreferenceStore();
			CTextTools textTools = CUIPlugin.getDefault().getTextTools();
			fSourceViewerConfiguration= new CustomCSourceViewerConfiguration(textTools.getColorManager(), fPreferenceStore, language);
			setDocumentPartitioning(fSourceViewerConfiguration.getConfiguredDocumentPartitioning(null));
			initializeDamagerRepairer(storage, textTools.getColorManager(), fPreferenceStore);
			fPreferenceStore.addPropertyChangeListener(this);
		}
	}

	private void initializeDamagerRepairer(IStorage storage, IColorManager colorManager, IPreferenceStore store) {
		String[] contentTypes= fSourceViewerConfiguration.getConfiguredContentTypes(null);
		for (int i = 0; i < contentTypes.length; ++i) {
			String contentType = contentTypes[i];
			ITokenScanner scanner;
			scanner = fSourceViewerConfiguration.getScannerForContentType(contentType);
			if (scanner != null) {
				if (fDamagerRepairer == null) {
					fSourceTagProvider = createSourceTagProvider(storage);
					fDamagerRepairer= new SourceTagDamagerRepairer(scanner, fSourceTagProvider, colorManager, store);
					if (fSourceTagProvider != null) {
						if (fSourceTagListener == null) {
							fSourceTagListener= new ISourceTagListener() {
								public void sourceTagsChanged(ISourceTagProvider provider) {
									handleSourceTagsChanged();
								}};
						}
						fSourceTagProvider.addSourceTagListener(fSourceTagListener);
					}
				}
				fDamagerRepairer.setScanner(contentType, scanner);
				setDamager(fDamagerRepairer, contentType);
				setRepairer(fDamagerRepairer, contentType);
			}
		}
	}

	/*
	 * @see org.eclipse.dd.dsf.debug.internal.ui.disassembly.presentation.ISourcePresentationCreator#dispose()
	 */
	public void dispose() {
		fViewer= null;
		fPresentation= null;
		if (fPreferenceStore != null) {
			fPreferenceStore.removePropertyChangeListener(this);
			fPreferenceStore= null;
		}
		if (fSourceViewerConfiguration != null) {
			fSourceViewerConfiguration.dispose();
			fSourceViewerConfiguration= null;
		}
		if (fSourceTagProvider != null) {
			if (fSourceTagListener != null) {
				fSourceTagProvider.removeSourceTagListener(fSourceTagListener);
				fSourceTagListener= null;
			}
			fSourceTagProvider= null;
		}
	}

	/*
	 * @see org.eclipse.dd.dsf.debug.internal.ui.disassembly.presentation.ISourcePresentationCreator#getPresentation(org.eclipse.jface.text.IRegion, org.eclipse.jface.text.IDocument)
	 */
	public TextPresentation getPresentation(IRegion region, IDocument document) {
		assert fViewer != null;
		if (fViewer == null) {
			return null;
		}
		if (fPresentation == null) {
			setDocumentToDamagers(document);
			setDocumentToRepairers(document);
			int docLength= document.getLength();
			if (docLength <= 128*1024) {
				IRegion all= new Region(0, docLength);
				fPresentation= createPresentation(all, document);
			} else {
				return createPresentation(region, document);
			}
		}
		fPresentation.setResultWindow(region);
		return fPresentation;
	}

	protected void handleSourceTagsChanged() {
		invalidateTextPresentation();
	}

	private void invalidateTextPresentation() {
		if (fPresentation != null) {
			fPresentation= null;
			if (fViewer != null) {
				Display display= fViewer.getTextWidget().getDisplay();
				if (display.getThread() != Thread.currentThread()) {
					display.asyncExec(new Runnable() {
						public void run() {
							if (fViewer != null) {
								fViewer.invalidateTextPresentation();
							}
						}});
				} else {
					fViewer.invalidateTextPresentation();
				}
			}
		}
	}

	private ISourceTagProvider createSourceTagProvider(IStorage storage) {
		ITranslationUnit tUnit= null;
		if (storage instanceof IFile) {
			tUnit= (ITranslationUnit) CoreModel.getDefault().create((IFile)storage);
		} else if (storage instanceof IFileState) {
			ICModel cModel= CoreModel.getDefault().getCModel();
			ICProject[] cProjects;
			try {
				cProjects = cModel.getCProjects();
				if (cProjects.length > 0) {
					tUnit= CoreModel.getDefault().createTranslationUnitFrom(cProjects[0], storage.getFullPath());
				}
			} catch (CModelException e) {
			}
		} else {
			IEditorInput input= EditorUtility.getEditorInputForLocation(storage.getFullPath(), null);
			if (input instanceof ITranslationUnitEditorInput) {
				tUnit= ((ITranslationUnitEditorInput)input).getTranslationUnit();
			}
		}
		if (tUnit != null) {
			return new CSourceTagProvider(tUnit);
		}
		return null;
	}

	/*
	 * @see org.eclipse.jface.util.IPropertyChangeListener#propertyChange(org.eclipse.jface.util.PropertyChangeEvent)
	 */
	public void propertyChange(PropertyChangeEvent event) {
		if (fSourceViewerConfiguration.affectsBehavior(event)) {
			fSourceViewerConfiguration.handlePropertyChangeEvent(event);
			invalidateTextPresentation();
		} else if (fDamagerRepairer.affectsBahvior(event)) {
			fDamagerRepairer.handlePropertyChangeEvent(event);
			invalidateTextPresentation();
		}
	}

}
