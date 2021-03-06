/*
 * #%L
 * org.eclipse.emf.eson
 * %%
 * Copyright (C) 2009 - 2014 Sebastian Benz, Michael Vorburger and others
 * %%
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * #L%
 */
package org.eclipse.emf.eson.building;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.apache.log4j.Logger;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EFactory;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EPackage;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.emf.eson.eFactory.Factory;
import org.eclipse.emf.eson.eFactory.Feature;
import org.eclipse.emf.eson.eFactory.NewObject;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;

public class ModelBuilder {
	
	private static Logger logger = Logger.getLogger(ModelBuilder.class);
	
	private NameAccessor nameSetter = new NameAccessor();
	private final FeatureSwitch featureSwitch = new FeatureSwitch();
	private BiMap<NewObject, EObject> mapping = HashBiMap.create();
	private List<ReferenceBuilder> deferredLinkingFeatureBuilder = new LinkedList<ReferenceBuilder>();

	// intentionally package local - outside clients shouldn't need to build individual NewObject, they only build(Factory)
	// NOTE: It is the caller's (!) responsibility to add the returned EObject into another EObject (or a Resource) eContainer. 
	Optional<EObject> build(NewObject newObject) throws ModelBuilderException {
		Preconditions.checkNotNull(newObject);
		EObject target = mapping.get(newObject);
		if (target != null) {
			return Optional.of(target);
		}
		Optional<EObject> eObjectOpt = createTarget(newObject);
		if (eObjectOpt.isPresent()) {
			EObject eObject = eObjectOpt.get();
			setName(eObject , newObject);
			buildFeatures(eObject, newObject.getFeatures());
		}
		return eObjectOpt;
	}

	private Optional<EObject> createTarget(NewObject from) throws ModelBuilderException {
		EClass eClass = from.getEClass();
		if (eClass == null) {
			logger.info("No EClass for New Object " + getNewObjectDescriptionForErrorMessage(from));
			return Optional.absent();
		}
		if (eClass.getEPackage() == null) {
			EcoreUtil.resolve(from.eClass(), from);
		}
		if (eClass.eIsProxy()) {
			logger.info("The EClass for NewObject " + getNewObjectDescriptionForErrorMessage(from) + " is still an unresolved EMF Proxy, something isn't working in your cross-Resource reference resolution");
			return Optional.absent();
		}
		EPackage ePackage = eClass.getEPackage();
		if (ePackage == null) {
			logger.info("No EPackage registered for EClass '" + eClass.getName() + "' defined in NewObject " + getNewObjectDescriptionForErrorMessage(from));
			return Optional.absent();
		}
		EFactory eFactoryInstance = ePackage.getEFactoryInstance();
		if (eFactoryInstance == null) {
			logger.info("No EFactory registered for " + ePackage.getNsURI());
			return Optional.absent();
		}
		EObject target = eFactoryInstance.create(eClass);
		mapping.put(from, target);
		return Optional.of(target);
	}

	private String getNewObjectDescriptionForErrorMessage(NewObject from) {
		return "name '" + from.getName()
		+ "' at URI " + from.eResource().getURI()
		+ "#" + from.eResource().getURIFragment(from);
	}

	/**
	 * Builds an EObject from a Factory.
	 * It is the caller's responsibility to add the returned EObject into a Resource as eContainer, failing to do so may result in dangling inter-Resource references. 
	 * 
	 * @param factory the Factory
	 * @return the EObject built from the Factory
	 * @throws ModelBuilderException if the content of the Factory prevented creation of a matching EObject
	 */
	public Optional<EObject> build(@NonNull Factory factory) throws ModelBuilderException {
		Optional<EObject> unlinkedRoot = buildWithoutLinking(factory);
		link();
		return unlinkedRoot;
	}

	public Optional<EObject> buildWithoutLinking(@NonNull Factory factory) throws ModelBuilderException {
		Preconditions.checkNotNull(factory);
		return build(factory.getRoot());
	}
	
	@SuppressWarnings("null")
	private void setName(EObject target, NewObject source) {
		String name = source.getName();
		if (name != null) {
			nameSetter.setName(source, target, name);
		}
	}

	private void buildFeatures(EObject eObject, List<Feature> features) throws ModelBuilderException {
		for (Feature feature : features) {
			FeatureBuilder featureBuilder = featureSwitch.doSwitch(feature);
			if (featureBuilder != null) {
				featureBuilder.modelBuilder(this).container(eObject).feature(feature).build();
			}
		}
	}

	public @NonNull EObject getBuilt(@NonNull NewObject newObject) throws ModelBuilderException {
		Preconditions.checkNotNull(newObject);
		checkNotEmpty();
		EObject target = mapping.get(newObject);
		if (target == null) {
			throw new IllegalArgumentException("NewObject passed as argument is not in this ModelBuilder, may be it's from a different Resource? " + newObject.toString());
		}
		return target;
	}
	
	/**
	 * Gets the "source" NewObject for the built EObject.
	 * 
	 * @param value the EObject
	 * @return new object, or null if the value EObject wasn't built by this ModelBuilder 
	 * @throws ModelBuilderException if build ModelBuilder is uninitialized, build() needs to called with non-empty Factory/NewObject before this. 
	 */
	public @Nullable NewObject getSource(@NonNull EObject value) throws ModelBuilderException {
		Preconditions.checkNotNull(value);
		checkNotEmpty();
		return mapping.inverse().get(value);
	}

	private void checkNotEmpty() throws ModelBuilderException {
		if (mapping.isEmpty())
			throw new ModelBuilderException("ModelBuilder is uninitialized, build() needs to called with non-empty Factory/NewObject before getSource()");
	}

	public void clear() {
		mapping.clear();
		deferredLinkingFeatureBuilder.clear();
	}

	public boolean isBuilt() {
		return !mapping.isEmpty();
	}

	public void addDeferredLinkingFeatureBuilder(ReferenceBuilder builder) {
		deferredLinkingFeatureBuilder.add(builder);
	}

	public void link() throws ModelBuilderException {
		Iterator<ReferenceBuilder> it = deferredLinkingFeatureBuilder.iterator();
		while (it.hasNext()) {
			ReferenceBuilder fb = it.next();
			try {
				fb.link();
			} catch (Throwable t) {
				logger.error("link() failed", t);
			} finally {
				it.remove();
			}
		}
	}
	
	public void putEObjectNewObjectPair(EObject eObject, NewObject newObject) {
		mapping.put(newObject, eObject);
	}
}
