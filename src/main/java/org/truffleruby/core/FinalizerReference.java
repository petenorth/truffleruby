/*
 * Copyright (c) 2019 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core;

import java.lang.ref.ReferenceQueue;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedList;
import java.util.Set;

import com.oracle.truffle.api.object.DynamicObject;
import org.truffleruby.language.objects.ObjectGraphNode;

public class FinalizerReference
        extends
        ReferenceProcessingService.PhantomProcessingReference<FinalizerReference, Object> implements ObjectGraphNode {

    /**
     * All accesses to this Deque must be synchronized by taking the
     * {@link FinalizationService} monitor, to avoid concurrent access.
     */
    private final Deque<FinalizationService.Finalizer> finalizers = new LinkedList<>();

    FinalizerReference(Object object, ReferenceQueue<? super Object> queue, FinalizationService service) {
        super(object, queue, service);
    }

    void addFinalizer(Class<?> owner, Runnable action, DynamicObject root) {
        finalizers.addLast(new FinalizationService.Finalizer(owner, action, root));
    }

    FinalizerReference removeFinalizers(FinalizationService finalizationService, Class<?> owner) {
        finalizers.removeIf(f -> f.getOwner() == owner);

        if (finalizers.isEmpty()) {
            finalizationService.remove(this);
            return null;
        } else {
            return this;
        }
    }

    FinalizationService.Finalizer getFirstFinalizer() {
        return finalizers.pollFirst();
    }

    void collectRoots(Collection<DynamicObject> roots) {
        for (FinalizationService.Finalizer finalizer : finalizers) {
            final DynamicObject root = finalizer.getRoot();
            if (root != null) {
                roots.add(root);
            }
        }
    }

    @Override
    public void getAdjacentObjects(Set<DynamicObject> reachable) {
        collectRoots(reachable);
    }
}