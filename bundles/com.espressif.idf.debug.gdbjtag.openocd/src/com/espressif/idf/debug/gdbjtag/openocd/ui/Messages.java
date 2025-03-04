/*******************************************************************************
 *  Copyright (c) 2008 QNX Software Systems and others.
 *
 *  This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License 2.0
 *  which accompanies this distribution, and is available at
 *  https://www.eclipse.org/legal/epl-2.0/
 *
 *  SPDX-License-Identifier: EPL-2.0
 * 
 *  Contributors:
 *      QNX Software Systems - initial API and implementation
 *      Liviu Ionescu - ARM version
 *******************************************************************************/

package com.espressif.idf.debug.gdbjtag.openocd.ui;

import java.util.MissingResourceException;
import java.util.ResourceBundle;

import org.eclipse.osgi.util.NLS;

import com.espressif.idf.debug.gdbjtag.openocd.Activator;

public class Messages
{

	// ------------------------------------------------------------------------

	private static final String MESSAGES = Activator.PLUGIN_ID + ".ui.messages"; //$NON-NLS-1$

	public static String OpenOcdFailedMsg;

	public static String ProjectMcuPagePropertyPage_description;
	public static String WorkspaceMcuPagePropertyPage_description;
	public static String GlobalMcuPagePropertyPage_description;

	public static String McuPage_executable_label;
	public static String McuPage_executable_folder;

	public static String BreakPointPage_RadioGroupTitle;
	public static String BreakPointPage_BtnStartHeapTrace;
	public static String BreakPointPage_BtnStopHeapTrace;
	public static String BreakPointPage_TextHeapDumpFileName;
	public static String BreakPointPage_BtnBrowse;

	public static String IDFLaunchTargetNotFoundIDFLaunchTargetNotFoundTitle;
	public static String IDFLaunchTargetNotFoundMsg1;
	public static String IDFLaunchTargetNotFoundMsg2;
	public static String IDFLaunchTargetNotFoundMsg3;

	public static String StartupTabOpenOcdGroup;
	public static String StartupTabFlashBeforeStart;
	public static String StartupTabEnableVerboseOutput;

	public static String MissingDebugConfigurationTitle;
	public static String DebugConfigurationNotFoundMsg;
	public static String AppLvlTracingJob;

	public static String DllNotFound_ExceptionMessage;
	// ------------------------------------------------------------------------

	static
	{
		// initialise resource bundle
		NLS.initializeMessages(MESSAGES, Messages.class);
	}

	private static ResourceBundle RESOURCE_BUNDLE;

	static
	{
		try
		{
			RESOURCE_BUNDLE = ResourceBundle.getBundle(MESSAGES);
		}
		catch (MissingResourceException e)
		{
			Activator.log(e);
		}
	}

	private Messages()
	{
	}

	public static String getString(String key)
	{
		try
		{
			return RESOURCE_BUNDLE.getString(key);
		}
		catch (MissingResourceException e)
		{
			return '!' + key + '!';
		}
	}

	public static ResourceBundle getResourceBundle()
	{
		return RESOURCE_BUNDLE;
	}

	// ------------------------------------------------------------------------
}
