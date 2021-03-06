/**********************************************************************
Copyright (c) 2006 Andy Jefferson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
    ...
**********************************************************************/
package org.datanucleus.api.jpa;

import java.lang.reflect.Field;

import javax.persistence.EntityManager;
import javax.persistence.EntityNotFoundException;
import javax.persistence.OptimisticLockException;
import javax.persistence.PersistenceException;

import org.datanucleus.ExecutionContext;
import org.datanucleus.enhancer.Persistable;
import org.datanucleus.exceptions.NucleusCanRetryException;
import org.datanucleus.exceptions.NucleusDataStoreException;
import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.exceptions.NucleusObjectNotFoundException;
import org.datanucleus.exceptions.NucleusOptimisticException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.state.ObjectProvider;
import org.datanucleus.state.StateManager;
import org.datanucleus.store.exceptions.ReachableObjectNotCascadedException;
import org.datanucleus.store.query.QueryTimeoutException;
import org.datanucleus.util.ClassUtils;

/**
 * Helper for persistence operations with JPA.
 */
public class NucleusJPAHelper
{
    // ------------------------------ Object Management --------------------------------

    public static Object getObjectId(Object obj)
    {
        if (obj instanceof Persistable)
        {
            return ((Persistable)obj).dnGetObjectId();
        }
        return null;
    }

    /**
     * Accessor for the EntityManager for the supplied (persistable) object.
     * If the object is detached or transient then returns null.
     * @param obj The persistable object
     * @return The entity manager for this object
     */
    public static EntityManager getEntityManager(Object obj)
    {
        if (obj instanceof Persistable)
        {
            return (JPAEntityManager) ((Persistable)obj).dnGetExecutionContext().getOwner();
        }
        return null;
    }

    /**
     * Convenience accessor for whether the object is persistent.
     * @param obj The object
     * @return Whether it is persistent
     */
    public static boolean isPersistent(Object obj)
    {
        if (obj instanceof Persistable)
        {
            return ((Persistable)obj).dnIsPersistent();
        }
        return false;
    }

    /**
     * Convenience accessor for whether the object is deleted.
     * @param obj The object
     * @return Whether it is deleted
     */
    public static boolean isDeleted(Object obj)
    {
        if (obj instanceof Persistable)
        {
            return ((Persistable)obj).dnIsDeleted();
        }
        return false;
    }

    /**
     * Convenience accessor for whether the object is detached.
     * @param obj The object
     * @return Whether it is persistent
     */
    public static boolean isDetached(Object obj)
    {
        if (obj instanceof Persistable)
        {
            return ((Persistable)obj).dnIsDetached();
        }
        return false;
    }

    /**
     * Convenience accessor for whether the object is transactional.
     * @param obj The object
     * @return Whether it is transactional
     */
    public static boolean isTransactional(Object obj)
    {
        if (obj instanceof Persistable)
        {
            return ((Persistable)obj).dnIsTransactional();
        }
        return false;
    }

    /**
     * Convenience method to return a string of the state of an object.
     * Will return things like "detached", "persistent", etc
     * @param obj The object
     * @return The state
     */
    public static String getObjectState(Object obj)
    {
        if (obj == null)
        {
            return null;
        }

        if (isDetached(obj))
        {
            return "detached";
        }
        else if (isPersistent(obj))
        {
            if (isTransactional(obj))
            {
                if (isDeleted(obj))
                {
                    return "persistent-deleted";
                }
                return "persistent";
            }
            // Likely HOLLOW for some reason
            return "persistent";
        }

        return "transient";
    }

    // ------------------------------ Convenience --------------------------------

    /**
     * Accessor for the jdoDetachedState field of a detached object.
     * The returned array is made up of :
     * <ul>
     * <li>0 - the identity of the object</li>
     * <li>1 - the version of the object (upon detach)</li>
     * <li>2 - loadedFields BitSet</li>
     * <li>3 - dirtyFields BitSet</li>
     * </ul>
     * @param obj The detached object
     * @return The detached state
     */
    public static Object[] getDetachedStateForObject(Object obj)
    {
        if (obj == null || !isDetached(obj))
        {
            return null;
        }
        try
        {
            Field fld = ClassUtils.getFieldForClass(obj.getClass(), "dnDetachedState");
            fld.setAccessible(true);
            return (Object[]) fld.get(obj);
        }
        catch (Exception e)
        {
            throw new NucleusException("Exception accessing dnDetachedState field", e);
        }
    }

    /**
     * Accessor for the names of the dirty fields of the persistable object.
     * @param obj The persistable object
     * @param em The Entity Manager (only required if the object is detached)
     * @return Names of the dirty fields
     */
    public static String[] getDirtyFields(Object obj, EntityManager em)
    {
        if (obj == null || !(obj instanceof Persistable))
        {
            return null;
        }
        Persistable pc = (Persistable)obj;

        if (isDetached(pc))
        {
            ExecutionContext ec = ((JPAEntityManager)em).getExecutionContext();

            // Temporarily attach a StateManager to access the detached field information
            ObjectProvider op = ec.getNucleusContext().getObjectProviderFactory().newForDetached(ec, pc, pc.dnGetObjectId(), null);
            pc.dnReplaceStateManager((StateManager) op);
            op.retrieveDetachState(op);
            String[] dirtyFieldNames = op.getDirtyFieldNames();
            pc.dnReplaceStateManager(null);

            return dirtyFieldNames;
        }

        ExecutionContext ec = ((JPAEntityManager)em).getExecutionContext();
        ObjectProvider op = ec.findObjectProvider(pc);
        return op == null ? null : op.getDirtyFieldNames();
    }

    /**
     * Accessor for the names of the loaded fields of the persistable object.
     * @param obj Persistable object
     * @param em The Entity Manager (only required if the object is detached)
     * @return Names of the loaded fields
     */
    public static String[] getLoadedFields(Object obj, EntityManager em)
    {
        if (obj == null || !(obj instanceof Persistable))
        {
            return null;
        }
        Persistable pc = (Persistable)obj;

        if (isDetached(pc))
        {
            // Temporarily attach a StateManager to access the detached field information
            ExecutionContext ec = ((JPAEntityManager)em).getExecutionContext();
            ObjectProvider op = ec.getNucleusContext().getObjectProviderFactory().newForDetached(ec, pc, pc.dnGetObjectId(), null);
            pc.dnReplaceStateManager((StateManager) op);
            op.retrieveDetachState(op);
            String[] loadedFieldNames = op.getLoadedFieldNames();
            pc.dnReplaceStateManager(null);

            return loadedFieldNames;
        }

        ExecutionContext ec = ((JPAEntityManager)em).getExecutionContext();
        ObjectProvider op = ec.findObjectProvider(pc);
        return op == null ? null : op.getLoadedFieldNames();
    }

    /**
     * Convenience method to convert a Nucleus exception into a JPA exception.
     * If the incoming exception has a "failed object" then create the new exception with
     * a failed object. Otherwise if the incoming exception has nested exceptions then
     * create this exception with those nested exceptions. Else create this exception with
     * the incoming exception as its nested exception.
     * @param ne NucleusException
     * @return The JPAException
     */
    public static PersistenceException getJPAExceptionForNucleusException(NucleusException ne)
    {
        if (ne instanceof ReachableObjectNotCascadedException)
        {
            // Reachable object not persistent but field doesn't allow cascade-persist
            throw new IllegalStateException(ne.getMessage());
        }
        else if (ne instanceof QueryTimeoutException)
        {
            return new javax.persistence.QueryTimeoutException(ne.getMessage(), ne);
        }
        else if (ne instanceof NucleusDataStoreException)
        {
            // JPA doesn't have "datastore" exceptions so just give a PersistenceException
            if (ne.getNestedExceptions() != null)
            {
                return new PersistenceException(ne.getMessage(), ne.getCause());
            }
            return new PersistenceException(ne.getMessage(), ne);
        }
        else if (ne instanceof NucleusCanRetryException)
        {
            // JPA doesn't have "retry" exceptions so just give a PersistenceException
            if (ne.getNestedExceptions() != null)
            {
                return new PersistenceException(ne.getMessage(), ne.getCause());
            }
            return new PersistenceException(ne.getMessage(), ne);
        }
        else if (ne instanceof NucleusObjectNotFoundException)
        {
            return new EntityNotFoundException(ne.getMessage());
        }
        else if (ne instanceof NucleusUserException)
        {
            // JPA doesnt have "user" exceptions so just give a PersistenceException
            if (ne.getNestedExceptions() != null)
            {
                return new PersistenceException(ne.getMessage(), ne.getCause());
            }
            return new PersistenceException(ne.getMessage(), ne);
        }
        else if (ne instanceof NucleusOptimisticException)
        {
            if (ne.getNestedExceptions() != null)
            {
                return new OptimisticLockException(ne.getMessage(), ne.getCause());
            }
            return new OptimisticLockException(ne.getMessage(), ne);
        }
        else
        {
            // JPA doesnt have "internal" exceptions so just give a PersistenceException
            if (ne.getNestedExceptions() != null)
            {
                return new PersistenceException(ne.getMessage(), ne.getCause());
            }
            return new PersistenceException(ne.getMessage(), ne);
        }
    }
}