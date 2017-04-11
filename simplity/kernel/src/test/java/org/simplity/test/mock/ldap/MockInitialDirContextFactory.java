package org.simplity.test.mock.ldap;

import java.util.Hashtable;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.DirContext;
import javax.naming.spi.InitialContextFactory;

import org.mockito.Mockito;

public class MockInitialDirContextFactory implements InitialContextFactory{
    private static DirContext mockContext = null;
    
    /** Returns the last DirContext (which is a Mockito mock) retrieved from this factory. */
    public static DirContext getLatestMockContext() {
        return mockContext;
    }
	
	@Override
	public Context getInitialContext(Hashtable<?, ?> environment) throws NamingException {
        synchronized(MockInitialDirContextFactory.class) {
            mockContext = (DirContext)
                Mockito.mock(DirContext.class);
        }
        return mockContext;
	}

}
