/*******************************************************************************
 * Copyright (c) 2008 QNX Software Systems and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * QNX Software Systems - Initial API and implementation
 * Ken Ryall (Nokia) - https://bugs.eclipse.org/bugs/show_bug.cgi?id=118894
 * IBM Corporation
 * Ericsson
 *******************************************************************************/
package org.eclipse.dd.gdb.internal.ui.launching;

import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.eclipse.cdt.core.CCorePlugin;
import org.eclipse.cdt.core.IBinaryParser;
import org.eclipse.cdt.core.ICExtensionReference;
import org.eclipse.cdt.core.IBinaryParser.IBinaryObject;
import org.eclipse.cdt.core.model.CoreModel;
import org.eclipse.cdt.core.settings.model.ICConfigurationDescription;
import org.eclipse.cdt.core.settings.model.ICProjectDescription;
import org.eclipse.cdt.debug.core.CDebugCorePlugin;
import org.eclipse.cdt.debug.core.ICDTLaunchConfigurationConstants;
import org.eclipse.cdt.debug.core.ICDebugConfiguration;
import org.eclipse.cdt.debug.core.ICDebugConstants;
import org.eclipse.cdt.debug.ui.ICDebuggerPage;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.dd.gdb.internal.provisional.IGDBLaunchConfigurationConstants;
import org.eclipse.dd.gdb.internal.provisional.launching.LaunchMessages;
import org.eclipse.dd.gdb.internal.provisional.service.SessionType;
import org.eclipse.dd.gdb.internal.ui.GdbUIPlugin;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

public class CDebuggerTab extends AbstractCDebuggerTab {
	
	private final static String LOCAL_DEBUGGER_ID = "org.eclipse.dd.gdb.GdbDebugger";//$NON-NLS-1$
	private final static String REMOTE_DEBUGGER_ID = "org.eclipse.dd.gdb.GdbServerDebugger";//$NON-NLS-1$
	
	protected boolean fAttachMode = false;
	protected boolean fRemoteMode = false;
	
	protected Button fStopInMain;
	protected Text fStopInMainSymbol;

	private ScrolledComposite fContainer;

	private Composite fContents;

	public CDebuggerTab(SessionType sessionType, boolean attach) {
		if (sessionType == SessionType.REMOTE) fRemoteMode = true;
		fAttachMode = attach;
		 
		ICDebugConfiguration dc = CDebugCorePlugin.getDefault().getDefaultDefaultDebugConfiguration();
		if (dc == null) {
			CDebugCorePlugin.getDefault().getPluginPreferences().setDefault(ICDebugConstants.PREF_DEFAULT_DEBUGGER_TYPE,
					                                                        LOCAL_DEBUGGER_ID);
		}
	}

	@Override
	public void createControl(Composite parent) {
		fContainer = new ScrolledComposite(parent, SWT.V_SCROLL | SWT.H_SCROLL);
		fContainer.setLayoutData(new GridData(GridData.FILL_BOTH));
		fContainer.setLayout(new FillLayout());
		fContainer.setExpandHorizontal(true);
		fContainer.setExpandVertical(true);
		
		fContents = new Composite(fContainer, SWT.NONE);
		setControl(fContainer);
		GdbUIPlugin.getDefault().getWorkbench().getHelpSystem().setHelp(getControl(),
				ICDTLaunchHelpContextIds.LAUNCH_CONFIGURATION_DIALOG_DEBBUGER_TAB);
		int numberOfColumns = (fAttachMode) ? 2 : 1;
		GridLayout layout = new GridLayout(numberOfColumns, false);
		fContents.setLayout(layout);
		GridData gd = new GridData(GridData.BEGINNING, GridData.CENTER, true, false);
		fContents.setLayoutData(gd);

		createDebuggerCombo(fContents, (fAttachMode) ? 1 : 2);
		createOptionsComposite(fContents);
		createDebuggerGroup(fContents, 2);
		
		fContainer.setContent(fContents);
	}

	protected void loadDebuggerComboBox(ILaunchConfiguration config, String selection) {
//		String configPlatform = getPlatform(config);
    	ICDebugConfiguration[] debugConfigs = CDebugCorePlugin.getDefault().getActiveDebugConfigurations();
		Arrays.sort(debugConfigs, new Comparator<ICDebugConfiguration>() {
			public int compare(ICDebugConfiguration c1, ICDebugConfiguration c2) {
				return Collator.getInstance().compare(c1.getName(), c2.getName());
			}
		});
		List<ICDebugConfiguration> list = new ArrayList<ICDebugConfiguration>();
		if (selection.equals("")) { //$NON-NLS-1$
			ICDebugConfiguration dc = CDebugCorePlugin.getDefault().getDefaultDebugConfiguration();
			if (dc == null) {
				CDebugCorePlugin.getDefault().saveDefaultDebugConfiguration(LOCAL_DEBUGGER_ID);
				dc = CDebugCorePlugin.getDefault().getDefaultDebugConfiguration();
			}
			if (dc != null)
				selection = dc.getID();
		}
		String defaultSelection = selection;
		for (ICDebugConfiguration debugConfig: debugConfigs) {
			if (((fRemoteMode || fAttachMode) && debugConfig.getID().equals(REMOTE_DEBUGGER_ID)) ||
                (!fRemoteMode && debugConfig.getID().equals(LOCAL_DEBUGGER_ID))) {
//				String debuggerPlatform = debugConfig.getPlatform();
//				if (validatePlatform(config, debugConfig)) {
					list.add(debugConfig);
//					// select first exact matching debugger for platform or
//					// requested selection
//					if ((defaultSelection.equals("") && debuggerPlatform.equalsIgnoreCase(configPlatform))) { //$NON-NLS-1$
//						defaultSelection = debugConfig.getID();
//					}
//				}
			}
		}
		// if no selection meaning nothing in config the force initdefault on tab
		setInitializeDefault(selection.equals("") ? true : false); //$NON-NLS-1$
		loadDebuggerCombo(list.toArray(new ICDebugConfiguration[list.size()]), defaultSelection);
	}

	@Override
	protected void updateComboFromSelection() {
		super.updateComboFromSelection();
		initializeCommonControls(getLaunchConfiguration());
	}

	@Override
	public void setDefaults(ILaunchConfigurationWorkingCopy config) {
		super.setDefaults(config);
		if (fAttachMode && fRemoteMode) {
			config.setAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_START_MODE,
					IGDBLaunchConfigurationConstants.DEBUGGER_MODE_REMOTE_ATTACH);
		} else if (fAttachMode) {
			config.setAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_START_MODE,
					ICDTLaunchConfigurationConstants.DEBUGGER_MODE_ATTACH);
		} else if (fRemoteMode) {
			config.setAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_START_MODE,
				    IGDBLaunchConfigurationConstants.DEBUGGER_MODE_REMOTE);
		} else {
			config.setAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_START_MODE,
					ICDTLaunchConfigurationConstants.DEBUGGER_MODE_RUN);
		}
		
		if (!fAttachMode) {
			config.setAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_STOP_AT_MAIN,
					ICDTLaunchConfigurationConstants.DEBUGGER_STOP_AT_MAIN_DEFAULT);
		}
		
		// Set the default debugger based on the active toolchain on the project (if possible)
		String defaultDebugger = null;
		try {
			String projectName = config.getAttribute(ICDTLaunchConfigurationConstants.ATTR_PROJECT_NAME, "");//$NON-NLS-1$
			if (projectName.length() > 0) {
				IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            	ICProjectDescription projDesc = CoreModel.getDefault().getProjectDescription(project);
            	ICConfigurationDescription configDesc = projDesc.getActiveConfiguration();
            	String configId = configDesc.getId();
        		ICDebugConfiguration[] debugConfigs = CDebugCorePlugin.getDefault().getActiveDebugConfigurations();
        		outer: for (int i = 0; i < debugConfigs.length; ++i) {
        			ICDebugConfiguration debugConfig = debugConfigs[i];
        			String[] patterns = debugConfig.getSupportedBuildConfigPatterns();
        			if (patterns != null) {
        				for (int j = 0; j < patterns.length; ++j) {
        					if (configId.matches(patterns[j])) {
        						defaultDebugger = debugConfig.getID();
        						break outer;
        					}
        				}
        			}
        		}
			}
		} catch (CoreException e) {
		}
		
		if (defaultDebugger == null) {
			ICDebugConfiguration dc = CDebugCorePlugin.getDefault().getDefaultDebugConfiguration();
			if (dc != null) {
				defaultDebugger = dc.getID();
			}
		}
		
		config.setAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_ID, defaultDebugger);
	}

	@Override
	public void initializeFrom(ILaunchConfiguration config) {
		setInitializing(true);
		super.initializeFrom(config);
		try {
			String id = config.getAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_ID, ""); //$NON-NLS-1$
			loadDebuggerComboBox(config, id);
			initializeCommonControls(config);
		} catch (CoreException e) {
		}
		setInitializing(false);
	}

	@Override
	public void performApply(ILaunchConfigurationWorkingCopy config) {
		super.performApply(config);
		
		if (fAttachMode && fRemoteMode) {
			config.setAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_START_MODE,
					IGDBLaunchConfigurationConstants.DEBUGGER_MODE_REMOTE_ATTACH);
		} else if (fAttachMode) {
			config.setAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_START_MODE,
					ICDTLaunchConfigurationConstants.DEBUGGER_MODE_ATTACH);
		} else if (fRemoteMode) {
			config.setAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_START_MODE,
				    IGDBLaunchConfigurationConstants.DEBUGGER_MODE_REMOTE);
		} else {
			config.setAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_START_MODE,
					ICDTLaunchConfigurationConstants.DEBUGGER_MODE_RUN);
		}
		if (!fAttachMode) {
			config.setAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_STOP_AT_MAIN,
					fStopInMain.getSelection());
			config.setAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_STOP_AT_MAIN_SYMBOL, 
				    fStopInMainSymbol.getText());

		}
	}

	@Override
	public boolean isValid(ILaunchConfiguration config) {
		if (!validateDebuggerConfig(config)) {
			return false;
		}
//		ICDebugConfiguration debugConfig = getDebugConfig();
//		if (fAttachMode && !debugConfig.supportsMode(ICDTLaunchConfigurationConstants.DEBUGGER_MODE_ATTACH)) {
//			setErrorMessage(MessageFormat.format(LaunchMessages.getString("CDebuggerTab.Mode_not_supported"), //$NON-NLS-1$
//					                            (Object[]) new String[]{ICDTLaunchConfigurationConstants.DEBUGGER_MODE_ATTACH})); 
//			return false;			
//		}
//		if (fRemoteMode && !debugConfig.supportsMode(IGDBLaunchConfigurationConstants.DEBUGGER_MODE_REMOTE)) {
//			setErrorMessage(MessageFormat.format(LaunchMessages.getString("CDebuggerTab.Mode_not_supported"), //$NON-NLS-1$
//					                            (Object[]) new String[]{IGDBLaunchConfigurationConstants.DEBUGGER_MODE_REMOTE})); 
//			return false;			
//		}
//		if (!fAttachMode && !fRemoteMode && !debugConfig.supportsMode(ICDTLaunchConfigurationConstants.DEBUGGER_MODE_RUN)) {
//			setErrorMessage(MessageFormat.format(LaunchMessages.getString("CDebuggerTab.Mode_not_supported"), //$NON-NLS-1$
//                    (Object[]) new String[]{ICDTLaunchConfigurationConstants.DEBUGGER_MODE_RUN}));
//			return false;
//		}
		if (fStopInMain != null && fStopInMainSymbol != null) {
			// The "Stop on startup at" field must not be empty
			String mainSymbol = fStopInMainSymbol.getText().trim();
			if (fStopInMain.getSelection() && mainSymbol.length() == 0) {
				setErrorMessage(LaunchMessages.getString("CDebuggerTab.Stop_on_startup_at_can_not_be_empty")); //$NON-NLS-1$
				return false;
			}
		}
		if (super.isValid(config) == false) {
			return false;
		}
		return true;
	}

	protected boolean validatePlatform(ILaunchConfiguration config, ICDebugConfiguration debugConfig) {
		String configPlatform = getPlatform(config);
		String debuggerPlatform = debugConfig.getPlatform();
		return (debuggerPlatform.equals("*") || debuggerPlatform.equalsIgnoreCase(configPlatform)); //$NON-NLS-1$
	}

	protected IBinaryObject getBinary(ILaunchConfiguration config) throws CoreException {
		String programName = null;
		String projectName = null;
		try {
			projectName = config.getAttribute(ICDTLaunchConfigurationConstants.ATTR_PROJECT_NAME, (String)null);
			programName = config.getAttribute(ICDTLaunchConfigurationConstants.ATTR_PROGRAM_NAME, (String)null);
		} catch (CoreException e) {
		}
		if (programName != null) {
			IPath exePath = new Path(programName);
			if (projectName != null && !projectName.equals("")) { //$NON-NLS-1$
				IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
				if (!project.isAccessible()) {
					return null;
				}
				if (!exePath.isAbsolute()) {
					exePath = project.getLocation().append(exePath);
				}
				ICExtensionReference[] parserRef = CCorePlugin.getDefault().getBinaryParserExtensions(project);
				for (int i = 0; i < parserRef.length; i++) {
					try {
						IBinaryParser parser = (IBinaryParser)parserRef[i].createExtension();
						IBinaryObject exe = (IBinaryObject)parser.getBinary(exePath);
						if (exe != null) {
							return exe;
						}
					} catch (ClassCastException e) {
					} catch (IOException e) {
					}
				}
			}
			IBinaryParser parser = CCorePlugin.getDefault().getDefaultBinaryParser();
			try {
				IBinaryObject exe = (IBinaryObject)parser.getBinary(exePath);
				return exe;
			} catch (ClassCastException e) {
			} catch (IOException e) {
			}
		}
		return null;
	}

	protected boolean validateDebuggerConfig(ILaunchConfiguration config) {
		ICDebugConfiguration debugConfig = getDebugConfig();
		if (debugConfig == null) {
			setErrorMessage(LaunchMessages.getString("CDebuggerTab.No_debugger_available")); //$NON-NLS-1$
			return false;
		}
		// We do not validate platform and CPU compatibility to avoid accidentally disabling
		// a valid configuration. It's much better to let an incompatible configuration through
		// than to disable a valid one.
		return true;
	}

	/**
	 * @see org.eclipse.debug.ui.AbstractLaunchConfigurationTab#updateLaunchConfigurationDialog()
	 */
	protected void update() {
		if (!isInitializing()) {
			super.updateLaunchConfigurationDialog();
		}
	}

	protected void createOptionsComposite(Composite parent) {
		Composite optionsComp = new Composite(parent, SWT.NONE);
		int numberOfColumns = (fAttachMode) ? 1 : 3;
		GridLayout layout = new GridLayout(numberOfColumns, false);
		optionsComp.setLayout(layout);
		optionsComp.setLayoutData(new GridData(GridData.BEGINNING, GridData.CENTER, true, false, 1, 1));
		if (!fAttachMode) {
			fStopInMain = createCheckButton(optionsComp, LaunchMessages.getString("CDebuggerTab.Stop_at_main_on_startup")); //$NON-NLS-1$
			fStopInMain.addSelectionListener(new SelectionAdapter() {

				@Override
				public void widgetSelected(SelectionEvent e) {
					fStopInMainSymbol.setEnabled(fStopInMain.getSelection());
					update();
				}
			});
			fStopInMainSymbol = new Text(optionsComp, SWT.SINGLE | SWT.BORDER);
			final GridData gridData = new GridData(GridData.FILL, GridData.CENTER, false, false);
			gridData.widthHint = 100;
			fStopInMainSymbol.setLayoutData(gridData);
			fStopInMainSymbol.addModifyListener(new ModifyListener() {
				public void modifyText(ModifyEvent evt) {
					update();
				}
			});
			fStopInMainSymbol.getAccessible().addAccessibleListener(
				new AccessibleAdapter() {                       
					@Override
					public void getName(AccessibleEvent e) {
                            e.result = LaunchMessages.getString("CDebuggerTab.Stop_at_main_on_startup"); //$NON-NLS-1$
					}
				}
			);
		}
	}

	@Override
	protected Shell getShell() {
		return super.getShell();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.debug.ui.ILaunchConfigurationTab#dispose()
	 */
	@Override
	public void dispose() {
		ICDebuggerPage debuggerPage = getDynamicTab();
		if (debuggerPage != null)
			debuggerPage.dispose();
		super.dispose();
	}

	protected void initializeCommonControls(ILaunchConfiguration config) {
		try {
			if (!fAttachMode) {
				fStopInMain.setSelection(config.getAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_STOP_AT_MAIN,
						ICDTLaunchConfigurationConstants.DEBUGGER_STOP_AT_MAIN_DEFAULT));
				fStopInMainSymbol.setText(config.getAttribute(ICDTLaunchConfigurationConstants.ATTR_DEBUGGER_STOP_AT_MAIN_SYMBOL,
						ICDTLaunchConfigurationConstants.DEBUGGER_STOP_AT_MAIN_SYMBOL_DEFAULT));
				fStopInMainSymbol.setEnabled(fStopInMain.getSelection());
			} else {
				// In attach mode, figure out if we are doing a remote connect based on the currently
				// chosen debugger
				if (getDebugConfig().getID().equals(REMOTE_DEBUGGER_ID)) fRemoteMode = true;
				else fRemoteMode = false;
			}
		} catch (CoreException e) {
		}
	}

	@Override
	protected void setInitializeDefault(boolean init) {
		super.setInitializeDefault(init);
	}
	
	@Override
	protected void contentsChanged() {
		fContainer.setMinSize(fContents.computeSize(SWT.DEFAULT, SWT.DEFAULT));
	}
}