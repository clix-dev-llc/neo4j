/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.core;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.neo4j.graphdb.ConstraintViolationException;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.helpers.collection.PrefetchingIterator;
import org.neo4j.kernel.impl.AbstractNeo4jTestCase;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.neo4j.kernel.impl.MyRelTypes.TEST;

class TestLoopRelationships extends AbstractNeo4jTestCase
{
    @Test
    void canCreateRelationshipBetweenTwoNodesWithLoopsThenDeleteOneOfTheNodesAndItsRelationships()
    {
        Node source = getGraphDb().createNode();
        Node target = getGraphDb().createNode();
        source.createRelationshipTo( source, TEST );
        target.createRelationshipTo( target, TEST );
        source.createRelationshipTo( target, TEST );

        newTransaction();

        for ( Relationship rel : target.getRelationships() )
        {
            rel.delete();
        }
        target.delete();
    }

    @Test
    void canDeleteNodeAfterDeletingItsRelationshipsIfThoseRelationshipsIncludeLoops()
    {
        Node node = getGraphDb().createNode();

        txCreateLoop( node );
        txCreateRel( node );
        txCreateLoop( node );

        for ( Relationship rel : node.getRelationships() )
        {
            rel.delete();
        }
        node.delete();

        commit();
    }

    private void txCreateRel( Node node )
    {
        node.createRelationshipTo( getGraphDb().createNode(), TEST );
        newTransaction();
    }

    private void txCreateLoop( Node node )
    {
        node.createRelationshipTo( node, TEST );
        newTransaction();
    }

    @Test
    void canAddLoopRelationship()
    {
        Node node = getGraphDb().createNode();
        node.createRelationshipTo( node, TEST );

        newTransaction();

        for ( Direction dir : Direction.values() )
        {
            int count = 0;
            for ( Relationship rel : node.getRelationships( dir ) )
            {
                count++;
                assertEquals( node, rel.getStartNode(), "start node" );
                assertEquals( node, rel.getEndNode(), "end node" );
                assertEquals( node, rel.getOtherNode( node ), "other node" );
            }
            assertEquals( 1, count, dir.name() + " relationship count" );
        }
    }

    @Test
    void canAddManyLoopRelationships()
    {
        testAddManyLoopRelationships( 2 );
        testAddManyLoopRelationships( 3 );
        testAddManyLoopRelationships( 5 );
    }

    private void testAddManyLoopRelationships( int count )
    {
        for ( boolean[] loop : permutations( count ) )
        {
            Node root = getGraphDb().createNode();
            Relationship[] relationships = new Relationship[count];
            for ( int i = 0; i < count; i++ )
            {
                if ( loop[i] )
                {
                    relationships[i] = root.createRelationshipTo( root, TEST );
                }
                else
                {
                    relationships[i] = root.createRelationshipTo( getGraphDb().createNode(), TEST );
                }
            }
            newTransaction();
            verifyRelationships( Arrays.toString( loop ), root, loop,
                    relationships );
        }
    }

    @Test
    void canAddLoopRelationshipAndOtherRelationships()
    {
        testAddLoopRelationshipAndOtherRelationships( 2 );
        testAddLoopRelationshipAndOtherRelationships( 3 );
        testAddLoopRelationshipAndOtherRelationships( 5 );
    }

    private void testAddLoopRelationshipAndOtherRelationships( int size )
    {
        for ( int i = 0; i < size; i++ )
        {
            Node root = getGraphDb().createNode();
            Relationship[] relationships = createRelationships( size, i, root );
            verifyRelationships( String.format( "loop on %s of %s", i, size ),
                    root, i, relationships );
        }
    }

    @Test
    void canAddAndRemoveLoopRelationshipAndOtherRelationships()
    {
        testAddAndRemoveLoopRelationshipAndOtherRelationships( 2 );
        testAddAndRemoveLoopRelationshipAndOtherRelationships( 3 );
        testAddAndRemoveLoopRelationshipAndOtherRelationships( 5 );
    }

    @Test
    void getSingleRelationshipOnNodeWithOneLoopOnly()
    {
        Node node = getGraphDb().createNode();
        Relationship singleRelationship = node.createRelationshipTo( node, TEST );
        assertEquals( singleRelationship, node.getSingleRelationship( TEST, Direction.OUTGOING ) );
        assertEquals( singleRelationship, node.getSingleRelationship( TEST, Direction.INCOMING ) );
        assertEquals( singleRelationship, node.getSingleRelationship( TEST, Direction.BOTH ) );
        commit();

        newTransaction();
        assertEquals( singleRelationship, node.getSingleRelationship( TEST, Direction.OUTGOING ) );
        assertEquals( singleRelationship, node.getSingleRelationship( TEST, Direction.INCOMING ) );
        assertEquals( singleRelationship, node.getSingleRelationship( TEST, Direction.BOTH ) );
        finish();
    }

    @Test
    void cannotDeleteNodeWithLoopStillAttached()
    {
        // Given
        GraphDatabaseService db = getGraphDb();
        Node node;
        try ( Transaction tx = db.beginTx() )
        {
            node = db.createNode();
            node.createRelationshipTo( node, RelationshipType.withName( "MAYOR_OF" ) );
            tx.success();
        }

        // And given a transaction deleting just the node
        Transaction tx = newTransaction();
        node.delete();
        tx.success();

        var e = assertThrows( ConstraintViolationException.class, tx::close );
        assertThat( e.getMessage(), equalTo( "Cannot delete node<" + node.getId() + ">, because it still has relationships. " +
            "To delete this node, you must first delete its relationships." ) );
    }

    @Test
    void getOtherNodeFunctionsCorrectly()
    {
        Node node = getGraphDb().createNode();
        Relationship relationship = node.createRelationshipTo( node, TEST );

        // This loop messes up the readability of the test case, but avoids duplicated
        // assertion code. Same assertions withing the transaction as after it has committed.
        for ( int i = 0; i < 2; i++ )
        {
            assertEquals( node, relationship.getOtherNode( node ) );
            assertEquals( asList( node, node ), asList( relationship.getNodes() ) );
            assertThrows( NotFoundException.class, () -> relationship.getOtherNode( getGraphDb().createNode() ) );
            newTransaction();
        }
    }

    @Test
    void getNewlyCreatedLoopRelationshipFromCache()
    {
        Node node = getGraphDb().createNode();
        node.createRelationshipTo( getGraphDb().createNode(), TEST );
        newTransaction();
        Relationship relationship = node.createRelationshipTo( node, TEST );
        newTransaction();
        assertEquals( relationship, node.getSingleRelationship( TEST, Direction.INCOMING ) );
    }

    private void testAddAndRemoveLoopRelationshipAndOtherRelationships( int size )
    {
        for ( boolean[] delete : permutations( size ) )
        {
            for ( int i = 0; i < size; i++ )
            {
                Node root = getGraphDb().createNode();
                Relationship[] relationships = createRelationships( size, i,
                        root );
                for ( int j = 0; j < size; j++ )
                {
                    if ( delete[j] )
                    {
                        relationships[j].delete();
                        relationships[j] = null;
                    }
                    newTransaction();
                }
                verifyRelationships( String.format(
                                "loop on %s of %s, delete %s", i, size,
                                Arrays.toString( delete ) ), root,
                        i, relationships );
            }
        }
    }

    private static Iterable<boolean[]> permutations( final int size )
    {
        final int max = 1 << size;
        return () -> new PrefetchingIterator<>()
        {
            int pos;

            @Override
            protected boolean[] fetchNextOrNull()
            {
                if ( pos < max )
                {
                    int cur = pos++;
                    boolean[] result = new boolean[size];
                    for ( int i = 0; i < size; i++ )
                    {
                        result[i] = (cur & 1) == 1;
                        cur >>= 1;
                    }
                    return result;
                }
                return null;
            }
        };
    }

    private Relationship[] createRelationships( int count, int loop, Node root )
    {
        Node[] nodes = new Node[count];
        for ( int i = 0; i < count; i++ )
        {
            if ( loop == i )
            {
                nodes[i] = root;
            }
            else
            {
                nodes[i] = getGraphDb().createNode();
            }
        }

        newTransaction();

        Relationship[] relationships = new Relationship[count];
        for ( int i = 0; i < count; i++ )
        {
            relationships[i] = root.createRelationshipTo( nodes[i], TEST );
            newTransaction();
        }
        return relationships;
    }

    private static void verifyRelationships( String message, Node root, int loop,
        Relationship... relationships )
    {
        boolean[] loops = new boolean[relationships.length];
        for ( int i = 0; i < relationships.length; i++ )
        {
            loops[i] = i == loop;
        }
        verifyRelationships( message, root, loops, relationships );
    }

    private static void verifyRelationships( String message, Node root,
        boolean[] loop, Relationship... relationships )
    {
        for ( Direction dir : Direction.values() )
        {
            Set<Relationship> expected = new HashSet<>();
            for ( int i = 0; i < relationships.length; i++ )
            {
                if ( relationships[i] != null
                        && (dir != Direction.INCOMING || loop[i]) )
                {
                    expected.add( relationships[i] );
                }
            }

            for ( Relationship rel : root.getRelationships( dir ) )
            {
                assertTrue( expected.remove( rel ), message + ": unexpected relationship: " + rel );
            }
            assertTrue( expected.isEmpty(), message + ": expected relationships not seen " + expected );
        }
    }
}
