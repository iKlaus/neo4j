/**
 * Copyright (c) 2002-2011 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.backup;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.util.Collections;

import org.neo4j.backup.check.ConsistencyCheck;
import org.neo4j.helpers.Args;
import org.neo4j.kernel.AbstractGraphDatabase;
import org.neo4j.kernel.Config;
import org.neo4j.kernel.EmbeddedGraphDatabase;
import org.neo4j.kernel.impl.nioneo.store.StoreAccess;
import org.neo4j.kernel.impl.transaction.xaframework.InMemoryLogBuffer;
import org.neo4j.kernel.impl.transaction.xaframework.XaDataSource;
import org.neo4j.kernel.impl.transaction.xaframework.XaLogicalLog.LogExtractor;

class RebuildFromLogs
{
    private final XaDataSource nioneo;
    private final StoreAccess stores;

    RebuildFromLogs( AbstractGraphDatabase graphdb )
    {
        this.nioneo = getDataSource( graphdb, Config.DEFAULT_DATA_SOURCE_NAME );
        this.stores = new StoreAccess( graphdb );
    }

    RebuildFromLogs applyTransactionsFrom( File sourceDir ) throws IOException
    {
        AbstractGraphDatabase graphdb = new EmbeddedGraphDatabase( sourceDir.getAbsolutePath(),
                Collections.singletonMap( Config.KEEP_LOGICAL_LOGS, "true" ) );
        try
        {
            XaDataSource nioneo = getDataSource( graphdb, Config.DEFAULT_DATA_SOURCE_NAME );
            LogExtractor extractor = nioneo.getLogExtractor( 2, nioneo.getLastCommittedTxId() );
            for ( InMemoryLogBuffer buffer = new InMemoryLogBuffer();; buffer.reset() )
            {
                long txId = extractor.extractNext( buffer );
                if ( txId == -1 ) break;
                applyTransaction( txId, buffer );
            }
        }
        finally
        {
            graphdb.shutdown();
        }
        return this;
    }

    public void applyTransaction( long txId, ReadableByteChannel txData ) throws IOException
    {
        nioneo.applyCommittedTransaction( txId, txData );
    }

    private static XaDataSource getDataSource( AbstractGraphDatabase graphdb, String name )
    {
        XaDataSource datasource = graphdb.getConfig().getTxModule().getXaDataSourceManager().getXaDataSource( name );
        if ( datasource == null ) throw new NullPointerException( "Could not access " + name );
        return datasource;
    }

    private static final String LOG_NAME_PREFIX = "nioneo_logical.log.v";

    public static void main( String[] args )
    {
        if ( args == null )
        {
            printUsage();
            return;
        }
        Args params = new Args( args );
        boolean full = params.getBoolean( "full", false, true );
        args = params.orphans().toArray( new String[0] );
        if ( args.length != 2 )
        {
            printUsage( "Exactly two positional arguments expected: <source dir with logs> <target dir for graphdb>" );
            System.exit( -1 );
            return;
        }
        File source = new File( args[0] ), target = new File( args[1] );
        if ( !source.isDirectory() )
        {
            printUsage( source + " is not a directory" );
            System.exit( -1 );
            return;
        }
        if ( target.exists() )
        {
            if ( target.isDirectory() )
            {
                if ( OnlineBackup.directoryContainsDb( target.getAbsolutePath() ) )
                {
                    printUsage( "target graph database already exists" );
                    System.exit( -1 );
                    return;
                }
                else
                {
                    System.err.println( "WARNING: the directory " + target + " already exists" );
                }
            }
            else
            {
                printUsage( target + " is a file" );
                System.exit( -1 );
                return;
            }
        }
        if ( findMaxLogFileId( source ) < 0 )
        {
            printUsage( "Inconsistent number of log files found in " + source );
            System.exit( -1 );
            return;
        }
        AbstractGraphDatabase graphdb = OnlineBackup.startTemporaryDb(
                target.getAbsolutePath(), full ? VerificationLevel.FULL_WITH_LOGGING : VerificationLevel.LOGGING );
        try
        {
            try
            {
                new RebuildFromLogs( graphdb ).applyTransactionsFrom( source ).fullCheck(!full);
            }
            finally
            {
                graphdb.shutdown();
            }
        }
        catch ( IOException e )
        {
            System.err.println();
            e.printStackTrace( System.err );
            System.exit( -1 );
            return;
        }
    }

    private void fullCheck( boolean full )
    {
        if ( full )
        {
            try
            {
                new ConsistencyCheck( stores, true ).run();
            }
            catch ( AssertionError summary )
            {
                System.err.println( summary.getMessage() );
            }
        }
    }

    private static void printUsage( String... msgLines )
    {
        for ( String line : msgLines ) System.err.println( line );
        System.err.println( Args.jarUsage( RebuildFromLogs.class, "[-full] <source dir with logs> <target dir for graphdb>" ) );
        System.err.println( "WHERE:   <source dir>  is the path for where transactions to rebuild from are stored" );
        System.err.println( "         <target dir>  is the path for where to create the new graph database" );
        System.err.println( "         -full     --  to run a full check over the entire store for each transaction" );
    }

    private static int findMaxLogFileId( File source )
    {
        int max = -1;
        for ( File file : source.listFiles( new FilenameFilter()
        {
            @Override
            public boolean accept( File dir, String name )
            {
                return name.startsWith( LOG_NAME_PREFIX );
            }
        } ) )
        {
            max = Math.max( max, Integer.parseInt( file.getName().substring( LOG_NAME_PREFIX.length() ) ) );
        }
        for ( int filenr = 0; filenr <= max; filenr++ )
        {
            if ( !new File( source, LOG_NAME_PREFIX + filenr ).isFile() ) return -1;
        }
        return max;
    }
}