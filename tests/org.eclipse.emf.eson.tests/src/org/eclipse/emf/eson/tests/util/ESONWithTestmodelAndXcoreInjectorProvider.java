/*
 * #%L
 * org.eclipse.emf.eson.tests
 * %%
 * Copyright (C) 2014 Michael Vorburger
 * %%
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * #L%
 */
package org.eclipse.emf.eson.tests.util;

import org.eclipse.emf.ecore.xcore.XcoreStandaloneSetup;

import com.google.inject.Injector;

public class ESONWithTestmodelAndXcoreInjectorProvider extends ESONWithTestmodelInjectorProvider {

	@Override
	protected Injector internalCreateInjector() {
		XcoreStandaloneSetup.doSetup();
		return super.internalCreateInjector();
	}

}
