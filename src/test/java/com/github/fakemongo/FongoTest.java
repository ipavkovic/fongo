package com.github.fakemongo;

import ch.qos.logback.classic.Level;
import static com.github.fakemongo.ExpectedMongoException.expectWriteConcernException;
import com.github.fakemongo.impl.ExpressionParser;
import com.github.fakemongo.impl.Util;
import com.github.fakemongo.junit.FongoRule;
import com.google.common.collect.Sets;
import com.mongodb.AggregationOutput;
import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.BulkWriteOperation;
import com.mongodb.BulkWriteResult;
import com.mongodb.CommandResult;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.DBRef;
import com.mongodb.DuplicateKeyException;
import com.mongodb.FongoDBCollection;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoException;
import com.mongodb.QueryBuilder;
import com.mongodb.ReplicaSetStatus;
import com.mongodb.ServerAddress;
import com.mongodb.WriteConcern;
import com.mongodb.WriteResult;
import com.mongodb.client.MongoDatabase;
import com.mongodb.util.FongoJSON;
import java.net.InetSocketAddress;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.assertj.core.api.Assertions;
import static org.assertj.core.api.Assertions.assertThat;
import org.assertj.core.util.Lists;
import org.bson.BSON;
import org.bson.Document;
import org.bson.Transformer;
import org.bson.types.Binary;
import org.bson.types.MaxKey;
import org.bson.types.MinKey;
import org.bson.types.ObjectId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.slf4j.LoggerFactory;

public class FongoTest {

  public final FongoRule fongoRule = new FongoRule(false);

  public final ExpectedException exception = ExpectedException.none();

  @Rule
  public TestRule rules = RuleChain.outerRule(exception).around(fongoRule);

  @Test
  public void testGetDb() {
    Fongo fongo = newFongo();
    DB db = fongo.getDB("db");
    assertNotNull(db);
    assertSame("getDB should be idempotent", db, fongo.getDB("db"));
    assertEquals(Arrays.asList(db), fongo.getUsedDatabases());
    assertEquals(Arrays.asList("db"), fongo.getDatabaseNames());
  }

  @Test
  public void testGetCollection() {
    DB db = fongoRule.getDB();
    DBCollection collection = db.getCollection("coll");
    assertNotNull(collection);
    assertSame("getCollection should be idempotent", collection, db.getCollection("coll"));
    assertSame("getCollection should be idempotent", collection, db.getCollectionFromString("coll"));
    assertEquals(newHashSet(), db.getCollectionNames());
    collection.insert(new BasicDBObject("_id", 1));

    final Set<String> expected;
    if (fongoRule.mustContainsSystemIndexes()) {
      expected = newHashSet("coll", "system.indexes");
    } else {
      expected = newHashSet("coll");
    }
    Assertions.assertThat(db.getCollectionNames()).isEqualTo(expected);
  }

  @Test
  public void should_not_throw_a_NPE_when_option_is_null() {
    DB db = fongoRule.getDB("db");
    final DBCollection coll = db.createCollection("coll", null);
    Assertions.assertThat(coll).isNotNull();
    final HashSet<String> expected;
    if (fongoRule.mustContainsSystemIndexes()) {
      expected = Sets.newHashSet("coll", "system.indexes");
    } else {
      expected = Sets.newHashSet("coll");
    }
    Assertions.assertThat(db.getCollectionNames()).isEqualTo(expected);
  }

  @Test
  public void should_createCollection_create_the_collection() {
    DB db = fongoRule.getDB("db");
    db.createCollection("coll", new BasicDBObject());

    final HashSet<String> expected;
    if (fongoRule.mustContainsSystemIndexes()) {
      expected = Sets.newHashSet("coll", "system.indexes");
    } else {
      expected = Sets.newHashSet("coll");
    }
    Assertions.assertThat(db.getCollectionNames()).isEqualTo(expected);
  }

  @Test
  public void should_not_create_twice() {
    DB db = fongoRule.getDB();
    db.createCollection("coll", new BasicDBObject());
    exception.expect(MongoCommandException.class);
//    exception.expectMessage("collection already exists");

    db.createCollection("coll", new BasicDBObject());
  }

  @Test
  public void testCountMethod() {
    DBCollection collection = newCollection();
    assertEquals(0, collection.count());
  }

  @Test
  public void testCountWithQueryCommand() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("n", 1));
    collection.insert(new BasicDBObject("n", 2));
    collection.insert(new BasicDBObject("n", 2));
    assertEquals(2, collection.count(new BasicDBObject("n", 2)));
  }

  @Test
  public void testCountOnCursor() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("n", 1));
    collection.insert(new BasicDBObject("n", 2));
    collection.insert(new BasicDBObject("n", 2));
    assertEquals(3, collection.find(QueryBuilder.start("n").exists(true).get()).count());
  }

  @Test
  public void testInsertIncrementsCount() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("name", "jon"));
    assertEquals(1, collection.count());
  }

  @Test
  public void testFindOne() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("name", "jon"));
    DBObject result = collection.findOne();
    assertNotNull(result);
    assertNotNull("should have an _id", result.get("_id"));
  }

  @Test
  public void testFindOneNoData() {
    DBCollection collection = newCollection();
    DBObject result = collection.findOne();
    assertNull(result);
  }

  @Test
  public void testFindOneInId() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    DBObject result = collection.findOne(new BasicDBObject("_id", new BasicDBObject("$in", Arrays.asList(1, 2))));
    assertEquals(new BasicDBObject("_id", 1), result);
  }

  @Test
  public void testFindOneInSetOfId() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    DBObject result = collection.findOne(new BasicDBObject("_id", new BasicDBObject("$in", newHashSet(1, 2))));
    assertEquals(new BasicDBObject("_id", 1), result);
  }

  @Test
  public void testFindOneInSetOfData() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("data", 1));
    DBObject result = collection.findOne(new BasicDBObject("data", new BasicDBObject("$in", newHashSet(1, 2))));
    assertEquals(new BasicDBObject("_id", 1).append("data", 1), result);
  }

  @Test
  public void testFindOneIn() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("date", 1));
    DBObject result = collection.findOne(new BasicDBObject("date", new BasicDBObject("$in", Arrays.asList(1, 2))), new BasicDBObject("date", 1).append("_id", 0));
    assertEquals(new BasicDBObject("date", 1), result);
  }

  /**
   * @see <a href="https://github.com/fakemongo/fongo/issues/76">
   * Querying indexed field in subdocument does not work, but works without index
   * </a>
   */
  @Test
  public void testFindOneIn_within_array_and_given_index_set() {
    DBCollection collection = newCollection();
    // prepare documents
    collection.insert(
        new BasicDBObject("_id", 1)
            .append("animals", new BasicDBObject[]{
                new BasicDBObject("name", "dolphin").append("type", "mammal"),
            }),
        new BasicDBObject("_id", 2)
            .append("animals", new BasicDBObject[]{
                new BasicDBObject("name", "horse").append("type", "mammal"),
                new BasicDBObject("name", "shark").append("type", "fish")
            }));
    // prepare index
    collection.createIndex(new BasicDBObject("animals.type", "text"));

    DBObject result = collection.findOne(new BasicDBObject("animals.type", new BasicDBObject("$in", new String[]{"fish"})));

    assertNotNull(result);
    assertEquals(2, result.get("_id"));
  }

  @Test
  public void testFindOneInWithArray() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2));

    DBObject result = collection.findOne(new BasicDBObject("_id", new BasicDBObject("$in", new Integer[]{1, 3})));
    assertEquals(new BasicDBObject("_id", 1), result);
  }

  @Test
  public void testFindOneNorId() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2));
    collection.insert(new BasicDBObject("_id", 3));
    DBObject result = collection.findOne(new BasicDBObject("$nor", Util.list(new BasicDBObject("_id", 1), new BasicDBObject("_id", 2))));
    assertEquals(new BasicDBObject("_id", 3), result);
  }

  @Test
  public void testFindOneOrId() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    DBObject result = collection.findOne(new BasicDBObject("$or", Util.list(new BasicDBObject("_id", 1), new BasicDBObject("_id", 2))));
    assertEquals(new BasicDBObject("_id", 1), result);
  }

  @Test
  public void testFindOneOrIdCollection() throws Exception {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    DBObject result = collection.findOne(new BasicDBObject("$or", newHashSet(new BasicDBObject("_id", 1), new BasicDBObject("_id", 2))));
    assertEquals(new BasicDBObject("_id", 1), result);
  }

  @Test
  public void testFindOneOrData() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("date", 1));
    DBObject result = collection.findOne(new BasicDBObject("$or", Util.list(new BasicDBObject("date", 1), new BasicDBObject("date", 2))));
    assertEquals(1, result.get("date"));
  }

  @Test
  public void testFindOneNinWithArray() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2));

    DBObject result = collection.findOne(new BasicDBObject("_id", new BasicDBObject("$nin", new Integer[]{1, 3})));
    assertEquals(new BasicDBObject("_id", 2), result);
  }

  @Test
  public void testFindOneAndIdCollection() throws Exception {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("data", 2));
    DBObject result = collection.findOne(new BasicDBObject("$and", newHashSet(new BasicDBObject("_id", 1), new BasicDBObject("data", 2))));
    assertEquals(new BasicDBObject("_id", 1).append("data", 2), result);
  }

  @Test
  public void testFindOneById() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    DBObject result = collection.findOne(new BasicDBObject("_id", 1));
    assertEquals(new BasicDBObject("_id", 1), result);

    assertEquals(null, collection.findOne(new BasicDBObject("_id", 2)));
  }

  @Test
  public void testFindOneWithFields() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject().append("name", "jon").append("foo", "bar"));
    DBObject result = collection.findOne(new BasicDBObject(), new BasicDBObject("foo", 1));
    assertNotNull(result);
    assertNotNull("should have an _id", result.get("_id"));
    assertEquals("property 'foo'", "bar", result.get("foo"));
    assertNull("should not have the property 'name'", result.get("name"));
  }

  @Test
  public void testFindInWithRegex() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2));
    collection.insert(new BasicDBObject("_id", "s"));
    collection.insert(new BasicDBObject("_id", "a"));
    collection.insert(new BasicDBObject("_id", "abbb"));

    DBCursor cursor = collection.find(new BasicDBObject("_id", new BasicDBObject("$in", new Object[]{1, "s", Pattern.compile("ab+")})));
    List<DBObject> dbObjects = cursor.toArray();
    assertEquals(3, dbObjects.size());
    assertTrue(dbObjects.contains(new BasicDBObject("_id", 1)));
    assertTrue(dbObjects.contains(new BasicDBObject("_id", "s")));
    assertTrue(dbObjects.contains(new BasicDBObject("_id", "abbb")));
  }

  @Test
  public void testFindWithQuery() {
    DBCollection collection = newCollection();

    collection.insert(new BasicDBObject("name", "jon"));
    collection.insert(new BasicDBObject("name", "leo"));
    collection.insert(new BasicDBObject("name", "neil"));
    collection.insert(new BasicDBObject("name", "neil"));
    DBCursor cursor = collection.find(new BasicDBObject("name", "neil"));
    assertEquals("should have two neils", 2, cursor.toArray().size());
  }

  @Test
  public void testFindWithNullOrNoFieldFilter() {
    DBCollection collection = newCollection();

    collection.insert(new BasicDBObject("name", "jon").append("group", "group1"));
    collection.insert(new BasicDBObject("name", "leo").append("group", "group1"));
    collection.insert(new BasicDBObject("name", "neil1").append("group", "group2"));
    collection.insert(new BasicDBObject("name", "neil2").append("group", null));
    collection.insert(new BasicDBObject("name", "neil3"));

    // check {group: null} vs {group: {$exists: false}} filter
    DBCursor cursor1 = collection.find(new BasicDBObject("group", null));
    assertEquals("should have two neils (neil2, neil3)", 2, cursor1.toArray().size());

    DBCursor cursor2 = collection.find(new BasicDBObject("group", new BasicDBObject("$exists", false)));
    assertEquals("should have one neil (neil3)", 1, cursor2.toArray().size());

    // same check but for fields which don't exist in DB
    DBCursor cursor3 = collection.find(new BasicDBObject("other", null));
    assertEquals("should return all documents", 5, cursor3.toArray().size());

    DBCursor cursor4 = collection.find(new BasicDBObject("other", new BasicDBObject("$exists", false)));
    assertEquals("should return all documents", 5, cursor4.toArray().size());
  }

  @Test
  public void testFindExcludingOnlyId() {
    DBCollection collection = newCollection();

    collection.insert(new BasicDBObject("_id", "1").append("a", 1));
    collection.insert(new BasicDBObject("_id", "2").append("a", 2));

    DBCursor cursor = collection.find(new BasicDBObject(), new BasicDBObject("_id", 0));
    assertEquals("should have 2 documents", 2, cursor.toArray().size());
    assertEquals(Arrays.asList(new BasicDBObject("a", 1), new BasicDBObject("a", 2)), cursor.toArray());
  }

  // See http://docs.mongodb.org/manual/reference/operator/elemMatch/
  @Test
  public void testFindElemMatch() {
    DBCollection collection = newCollection();
    collection.insert((DBObject) FongoJSON.parse("{ _id:1, array: [ { value1:1, value2:0 }, { value1:2, value2:2 } ] }"));
    collection.insert((DBObject) FongoJSON.parse("{ _id:2, array: [ { value1:1, value2:0 }, { value1:1, value2:2 } ] }"));

    DBCursor cursor = collection.find((DBObject) FongoJSON.parse("{ array: { $elemMatch: { value1: 1, value2: { $gt: 1 } } } }"));
    assertEquals(Arrays.asList((DBObject) FongoJSON.parse("{ _id:2, array: [ { value1:1, value2:0 }, { value1:1, value2:2 } ] }")
    ), cursor.toArray());
  }

  // See http://docs.mongodb.org/manual/reference/operator/query/elemMatch/
  @Test
  public void should_elemMatch_from_manual_works() {
    DBCollection collection = newCollection();

    collection.insert((DBObject) FongoJSON.parse("{ _id: 1, results: [ 82, 85, 88 ] }"));
    collection.insert((DBObject) FongoJSON.parse("{ _id: 2, results: [ 75, 88, 89 ] }"));

    DBCursor cursor = collection.find((DBObject) FongoJSON.parse("{ results: { $elemMatch: { $gte: 80, $lt: 85 } } }"));
    assertEquals(Arrays.asList((DBObject) FongoJSON.parse("{ \"_id\" : 1, \"results\" : [ 82, 85, 88 ] }")
    ), cursor.toArray());
  }

  // See http://docs.mongodb.org/manual/reference/operator/query/elemMatch/
  @Test
  public void should_elemMatch_array_of_embedded_documents() {
    DBCollection collection = newCollection();

    collection.insert((DBObject) FongoJSON.parse("{ _id: 1, results: [ { product: \"abc\", score: 10 }, { product: \"xyz\", score: 5 } ] }"));
    collection.insert((DBObject) FongoJSON.parse("{ _id: 2, results: [ { product: \"abc\", score: 8 }, { product: \"xyz\", score: 7 } ] }"));
    collection.insert((DBObject) FongoJSON.parse("{ _id: 3, results: [ { product: \"abc\", score: 7 }, { product: \"xyz\", score: 8 } ] }"));


    DBCursor cursor = collection.find((DBObject) FongoJSON.parse("{ results: { $elemMatch: { product: \"xyz\", score: { $gte: 8 } } } }"));
    assertEquals(Arrays.asList((DBObject) FongoJSON.parse("{ \"_id\" : 3, \"results\" : [ { \"product\" : \"abc\", \"score\" : 7 }, { \"product\" : \"xyz\", \"score\" : 8 } ] }")
    ), cursor.toArray());
  }

  // See http://docs.mongodb.org/manual/reference/command/text/
  @Test
  @Ignore("TODO : FIXME WITH REAL $text QUERY")
  public void testCommandTextSearch() {
    // Given
    DBCollection collection = newCollection();
    collection.insert((DBObject) FongoJSON.parse("{ _id:1, textField: \"aaa bbb\" }"));
    collection.insert((DBObject) FongoJSON.parse("{ _id:2, textField: \"ccc ddd\" }"));
    collection.insert((DBObject) FongoJSON.parse("{ _id:3, textField: \"eee fff\" }"));
    collection.insert((DBObject) FongoJSON.parse("{ _id:4, textField: \"aaa eee\" }"));

    collection.createIndex(new BasicDBObject("textField", "text"));

    // When
    DBObject textSearchCommand = new BasicDBObject();
//		textSearchCommand.put("text", Interest.COLLECTION);
    textSearchCommand.put("search", "aaa -eee");

    DBObject textSearchResult = collection.getDB()
        .command(new BasicDBObject("text", textSearchCommand).append("db", collection.getName()));


    // Then
    ServerAddress serverAddress = new ServerAddress(new InetSocketAddress(ServerAddress.defaultHost(), ServerAddress.defaultPort()));
    String host = serverAddress.getHost() + ":" + serverAddress.getPort();
    DBObject expected = new BasicDBObject("serverUsed", host).append("ok", 1.0);
    expected.put("results", FongoJSON.parse("[ "
        + "{ \"score\" : 0.75 , "
        + "\"obj\" : { \"_id\" : 1 , \"textField\" : \"aaa bbb\"}}]"
    ));
    expected.put("stats",
        new BasicDBObject("nscannedObjects", 4L)
            .append("nscanned", 2L)
            .append("n", 1L)
            .append("timeMicros", 1)
    );

    if (fongoRule.isRealMongo()) {
      expected.removeField("serverUsed");
      textSearchResult.removeField("serverUsed");
    }
    Assertions.assertThat(textSearchResult).isEqualTo(expected);
    assertEquals("aaa bbb",
        ((DBObject) ((DBObject) ((List) textSearchResult.get("results")).get(0)).get("obj")).get("textField"));
  }

  @Test
  @Ignore("TODO : FIXME WITH REAL $text QUERY")
  public void testCommandTextSearchShouldNotWork() {
    DBCollection collection = newCollection();
    collection.insert((DBObject) FongoJSON.parse("{ _id:1, textField: \"aaa bbb\" }"));
    collection.insert((DBObject) FongoJSON.parse("{ _id:2, textField: \"ccc ddd\" }"));
    collection.insert((DBObject) FongoJSON.parse("{ _id:3, textField: \"eee fff\" }"));
    collection.insert((DBObject) FongoJSON.parse("{ _id:4, textField: \"aaa eee\" }"));

    collection.createIndex(new BasicDBObject("textField", "text"));

    DBObject textSearchCommand = new BasicDBObject();
//		textSearchCommand.put("text", Interest.COLLECTION);
    textSearchCommand.put("search", "aaa -eee");

    DBObject textSearchResult = collection.getDB()
        .command(new BasicDBObject(collection.getName(), new BasicDBObject("text", textSearchCommand)));
    DBObject expected = new BasicDBObject("ok", 0.0).append("errmsg", "no such cmd: db").append("code", 59).append("bad cmd", new BasicDBObject("db", new BasicDBObject("text", new BasicDBObject("search", "aaa -eee"))));
//    expected.put("results", JSON.parse("[ "
//            + "{ \"score\" : 0.75 , "
//            + "\"obj\" : { \"_id\" : 1 , \"textField\" : \"aaa bbb\"}}]"
//    ));
    textSearchResult.removeField("serverUsed");
    Assertions.assertThat(textSearchResult).isEqualTo(expected);
  }

  @Test
  public void testFindWithLimit() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2));
    collection.insert(new BasicDBObject("_id", 3));
    collection.insert(new BasicDBObject("_id", 4));

    DBCursor cursor = collection.find().limit(2);
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 1),
        new BasicDBObject("_id", 2)
    ), cursor.toArray());
  }

  @Test
  public void should_dbcursor_handle_limit_when_copy() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2));
    collection.insert(new BasicDBObject("_id", 3));
    collection.insert(new BasicDBObject("_id", 4));

    DBCursor cursor = collection.find().limit(2);
    DBCursor cursor1 = cursor.copy();
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 1),
        new BasicDBObject("_id", 2)
    ), cursor1.toArray());
  }

  @Test
  public void should_dbcursor_handle_limit_when_copy_and_not_modify_other() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2));
    collection.insert(new BasicDBObject("_id", 3));
    collection.insert(new BasicDBObject("_id", 4));

    DBCursor cursor = collection.find();
    DBCursor cursor1 = cursor.copy().limit(2);
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 1),
        new BasicDBObject("_id", 2),
        new BasicDBObject("_id", 3),
        new BasicDBObject("_id", 4)
    ), cursor.toArray());
  }

  @Test
  public void testFindWithSkipLimit() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2));
    collection.insert(new BasicDBObject("_id", 3));
    collection.insert(new BasicDBObject("_id", 4));

    DBCursor cursor = collection.find().limit(2).skip(2);
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 3),
        new BasicDBObject("_id", 4)
    ), cursor.toArray());
  }

  @Test
  public void testFindWithSkipLimitNoResult() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2));
    collection.insert(new BasicDBObject("_id", 3));
    collection.insert(new BasicDBObject("_id", 4));
    collection.insert(new BasicDBObject("_id", 5));

    QueryBuilder builder = new QueryBuilder().start("_id").greaterThanEquals(1).lessThanEquals(4);

    DBCursor cursor = collection.find(builder.get()).limit(2).skip(4);
    assertEquals(Arrays.asList(), cursor.toArray());
  }

  @Test
  public void testFindWithSkipLimitWithSort() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("date", 5L).append("str", "1"));
    collection.insert(new BasicDBObject("_id", 2).append("date", 6L).append("str", "2"));
    collection.insert(new BasicDBObject("_id", 3).append("date", 7L).append("str", "3"));
    collection.insert(new BasicDBObject("_id", 4).append("date", 8L).append("str", "4"));
    collection.insert(new BasicDBObject("_id", 5).append("date", 5L));

    QueryBuilder builder = new QueryBuilder().start("_id").greaterThanEquals(1).lessThanEquals(5).and("str").in(Arrays.asList("1", "2", "3", "4"));

    // Without sort.
    DBCursor cursor = collection.find(builder.get()).limit(2).skip(4);
    assertEquals(Arrays.asList(), cursor.toArray());

    // With sort.
    cursor = collection.find(builder.get()).sort(new BasicDBObject("date", 1)).limit(2).skip(4);
    assertEquals(Arrays.asList(), cursor.toArray());
  }

  @Test
  public void testFindWithWhere() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("say", "hi").append("n", 3),
        new BasicDBObject("_id", 2).append("say", "hello").append("n", 5));

    DBCursor cursor = collection.find(new BasicDBObject("$where", "this.say == 'hello'"));
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 2).append("say", "hello").append("n", 5)
    ), cursor.toArray());

    cursor = collection.find(new BasicDBObject("$where", "this.n < 4"));
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 1).append("say", "hi").append("n", 3)
    ), cursor.toArray());
  }

  @Test
  public void findWithEmptyQueryFieldValue() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("a", 2));
    collection.insert(new BasicDBObject("_id", 2).append("a", new BasicDBObject()));

    DBCursor cursor = collection.find(new BasicDBObject("a", new BasicDBObject()));
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 2).append("a", new BasicDBObject())
    ), cursor.toArray());
  }

  @Test
  public void testIdInQueryResultsInIndexOrder() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 4));
    collection.insert(new BasicDBObject("_id", 3));
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2));

    DBCursor cursor = collection.find(new BasicDBObject("_id",
        new BasicDBObject("$in", Arrays.asList(3, 2, 1))));
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 1),
        new BasicDBObject("_id", 2),
        new BasicDBObject("_id", 3)
    ), cursor.toArray());
  }

  @Test
  public void testIdInQueryResultsInIndexOrderEvenIfOrderByExistAndIsWrong() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 4));
    collection.insert(new BasicDBObject("_id", 3));
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2));

    DBCursor cursor = collection.find(new BasicDBObject("_id",
        new BasicDBObject("$in", Arrays.asList(3, 2, 1)))).sort(new BasicDBObject("wrongField", 1));
    Assertions.assertThat(cursor.toArray()).containsOnly(
        new BasicDBObject("_id", 1),
        new BasicDBObject("_id", 2),
        new BasicDBObject("_id", 3));
  }

  /**
   * Must return in inserted order.
   */
  @Test
  public void testIdInsertedOrder() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 4));
    collection.insert(new BasicDBObject("_id", 3));
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2));

    DBCursor cursor = collection.find();
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 4),
        new BasicDBObject("_id", 3),
        new BasicDBObject("_id", 1),
        new BasicDBObject("_id", 2)
    ), cursor.toArray());
  }

  @Test
  public void testSort() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("a", 1).append("_id", 1));
    collection.insert(new BasicDBObject("a", 2).append("_id", 2));
    collection.insert(new BasicDBObject("_id", 5));
    collection.insert(new BasicDBObject("a", 3).append("_id", 3));
    collection.insert(new BasicDBObject("a", 4).append("_id", 4));

    DBCursor cursor = collection.find().sort(new BasicDBObject("a", -1));
    assertEquals(Arrays.asList(
        new BasicDBObject("a", 4).append("_id", 4),
        new BasicDBObject("a", 3).append("_id", 3),
        new BasicDBObject("a", 2).append("_id", 2),
        new BasicDBObject("a", 1).append("_id", 1),
        new BasicDBObject("_id", 5)
    ), cursor.toArray());
  }

  @Test
  public void testCompoundSort() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("a", 1).append("_id", 1));
    collection.insert(new BasicDBObject("a", 2).append("_id", 5));
    collection.insert(new BasicDBObject("a", 1).append("_id", 2));
    collection.insert(new BasicDBObject("a", 2).append("_id", 4));
    collection.insert(new BasicDBObject("a", 1).append("_id", 3));

    DBCursor cursor = collection.find().sort(new BasicDBObject("a", 1).append("_id", -1));
    assertEquals(Arrays.asList(
        new BasicDBObject("a", 1).append("_id", 3),
        new BasicDBObject("a", 1).append("_id", 2),
        new BasicDBObject("a", 1).append("_id", 1),
        new BasicDBObject("a", 2).append("_id", 5),
        new BasicDBObject("a", 2).append("_id", 4)
    ), cursor.toArray());
  }

  @Test
  public void testCompoundSortFindAndModify() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("a", 1).append("_id", 1));
    collection.insert(new BasicDBObject("a", 2).append("_id", 5));
    collection.insert(new BasicDBObject("a", 1).append("_id", 2));
    collection.insert(new BasicDBObject("a", 2).append("_id", 4));
    collection.insert(new BasicDBObject("a", 1).append("_id", 3));

    DBObject object = collection.findAndModify(null, new BasicDBObject("a", 1).append("_id", -1), new BasicDBObject("date", 1));
    assertEquals(
        new BasicDBObject("_id", 3).append("a", 1), object);
  }

  @Test
  public void testCommandFindAndModify() {
    // Given
    DBCollection collection = newCollection();
    DB db = collection.getDB();
    collection.insert(new BasicDBObject("a", 1).append("_id", 1));
    collection.insert(new BasicDBObject("a", 2).append("_id", 5));
    collection.insert(new BasicDBObject("a", 1).append("_id", 2));
    collection.insert(new BasicDBObject("a", 2).append("_id", 4));
    collection.insert(new BasicDBObject("a", 1).append("_id", 3));

    // When
    CommandResult result = db.command(new BasicDBObject("findAndModify", collection.getName()).append("sort", new BasicDBObject("a", 1).append("_id", -1)).append("update", new BasicDBObject("date", 1)));

    // Then
    assertTrue(result.ok());
    assertEquals(
        new BasicDBObject("_id", 3).append("a", 1), result.get("value"));
  }

  @Test
  public void testEmbeddedSort() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2));
    collection.insert(new BasicDBObject("_id", 3));
    collection.insert(new BasicDBObject("_id", 4).append("counts", new BasicDBObject("done", 1)));
    collection.insert(new BasicDBObject("_id", 5).append("counts", new BasicDBObject("done", 2)));

    DBCursor cursor = collection.find(new BasicDBObject("c", new BasicDBObject("$ne", true))).sort(new BasicDBObject("counts.done", -1));
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 5).append("counts", new BasicDBObject("done", 2)),
        new BasicDBObject("_id", 4).append("counts", new BasicDBObject("done", 1)),
        new BasicDBObject("_id", 1),
        new BasicDBObject("_id", 2),
        new BasicDBObject("_id", 3)
    ), cursor.toArray());
  }

  @Test
  public void testBasicUpdate() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2).append("b", 5));
    collection.insert(new BasicDBObject("_id", 3));
    collection.insert(new BasicDBObject("_id", 4));

    collection.update(new BasicDBObject("_id", 2), new BasicDBObject("a", 5));

    assertEquals(new BasicDBObject("_id", 2).append("a", 5),
        collection.findOne(new BasicDBObject("_id", 2)));
  }

  @Test
  public void testFullUpdateWithSameId() throws Exception {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2).append("b", 5));
    collection.insert(new BasicDBObject("_id", 3));
    collection.insert(new BasicDBObject("_id", 4));

    collection.update(
        new BasicDBObject("_id", 2).append("b", 5),
        new BasicDBObject("_id", 2).append("a", 5));

    assertEquals(new BasicDBObject("_id", 2).append("a", 5),
        collection.findOne(new BasicDBObject("_id", 2)));
  }

  @Test
  public void testIdNotAllowedToBeUpdated() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));

    expectWriteConcernException(exception, 16837);
    collection.update(new BasicDBObject("_id", 1), new BasicDBObject("_id", 2).append("a", 5));
  }

  @Test
  public void testUpsert() {
    DBCollection collection = newCollection();
    WriteResult result = collection.update(new BasicDBObject("_id", 1).append("n", "jon"),
        new BasicDBObject("$inc", new BasicDBObject("a", 1)), true, false);
    assertEquals(new BasicDBObject("_id", 1).append("n", "jon").append("a", 1),
        collection.findOne());
    assertFalse(result.isUpdateOfExisting());
  }

  @Test
  public void testUpsertExisting() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    WriteResult result = collection.update(new BasicDBObject("_id", 1),
        new BasicDBObject("$inc", new BasicDBObject("a", 1)), true, false);
    assertEquals(new BasicDBObject("_id", 1).append("a", 1),
        collection.findOne());
    assertTrue(result.isUpdateOfExisting());
  }

  @Test
  public void testUpsertWithConditional() {
    DBCollection collection = newCollection();
    collection.update(new BasicDBObject("_id", 1).append("b", new BasicDBObject("$gt", 5)),
        new BasicDBObject("$inc", new BasicDBObject("a", 1)), true, false);
    assertEquals(new BasicDBObject("_id", 1).append("a", 1),
        collection.findOne());
  }

  @Test
  public void testUpsertWithIdIn() {
    DBCollection collection = newCollection();
    final ObjectId objectId = ObjectId.get();
    DBObject query = new BasicDBObjectBuilder().push("_id").append("$in", Arrays.asList(objectId)).pop().get();
    DBObject update = new BasicDBObjectBuilder()
        .push("$push").push("n").append("_id", 2).append("u", 3).pop().pop()
        .push("$inc").append("c", 4).pop().get();
    DBObject expected = new BasicDBObjectBuilder().append("n", Arrays.asList(new BasicDBObject("_id", 2).append("u", 3))).append("c", 4).get();
    collection.update(query, update, true, false);
    final DBObject result = collection.findOne();
    Assertions.assertThat(result.get("_id")).isNotEqualTo(objectId);
    result.removeField("_id");
    assertEquals(expected, result);
  }

  @Test
  public void testUpdateWithIdIn() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    DBObject query = new BasicDBObjectBuilder().push("_id").append("$in", Arrays.asList(1)).pop().get();
    DBObject update = new BasicDBObjectBuilder()
        .push("$push").push("n").append("_id", 2).append("u", 3).pop().pop()
        .push("$inc").append("c", 4).pop().get();
    DBObject expected = new BasicDBObjectBuilder().append("_id", 1).append("n", Arrays.asList(new BasicDBObject("_id", 2).append("u", 3))).append("c", 4).get();
    collection.update(query, update, false, true);
    assertEquals(expected, collection.findOne());
  }

  @Test
  public void testUpdateNotExistingById() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("n", "1"));
    collection.update(new BasicDBObject("_id", 2), new BasicDBObject("n", "2"));
    assertEquals(1, collection.find().size());
  }

  @Test
  public void testUpdateWithObjectId() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", new BasicDBObject("n", 1)));
    DBObject query = new BasicDBObject("_id", new BasicDBObject("n", 1));
    DBObject update = new BasicDBObject("$set", new BasicDBObject("a", 1));
    collection.update(query, update, false, false);
    assertEquals(new BasicDBObject("_id", new BasicDBObject("n", 1)).append("a", 1), collection.findOne());
  }

  @Test
  public void testUpdateWithIdInMulti() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1), new BasicDBObject("_id", 2));
    collection.update(new BasicDBObject("_id", new BasicDBObject("$in", Arrays.asList(1, 2))),
        new BasicDBObject("$set", new BasicDBObject("n", 1)), false, true);
    List<DBObject> results = collection.find().toArray();
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 1).append("n", 1),
        new BasicDBObject("_id", 2).append("n", 1)
    ), results);

  }

  @Test
  public void testUpdateWithIdQuery() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1), new BasicDBObject("_id", 2));
    collection.update(new BasicDBObject("_id", new BasicDBObject("$gt", 1)),
        new BasicDBObject("$set", new BasicDBObject("n", 1)), false, true);
    List<DBObject> results = collection.find().toArray();
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 1),
        new BasicDBObject("_id", 2).append("n", 1)
    ), results);

  }

  @Test
  public void testUpdateWithOneRename() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("a", new BasicDBObject("b", 1)));
    collection.update(new BasicDBObject("_id", 1), new BasicDBObject("$rename", new BasicDBObject("a.b", "a.c")));
    List<DBObject> results = collection.find().toArray();
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 1).append("a", new BasicDBObject("c", 1))
    ), results);
  }

  @Test
  public void testUpdateWithMultipleRenames() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("a", new BasicDBObject("b", 1))
        .append("x", 3)
        .append("h", new BasicDBObject("i", 8)));
    collection.update(new BasicDBObject("_id", 1), new BasicDBObject("$rename", new BasicDBObject("a.b", "a.c")
        .append("x", "y")
        .append("h.i", "u.r")));
    List<DBObject> results = collection.find().toArray();
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 1).append("a", new BasicDBObject("c", 1))
            .append("y", 3)
            .append("u", new BasicDBObject("r", 8))
            .append("h", new BasicDBObject())
    ), results);
  }

  @Test
  public void testCompoundDateIdUpserts() {
    DBCollection collection = newCollection();
    DBObject query = new BasicDBObjectBuilder().push("_id")
        .push("$lt").add("n", "a").add("t", 10).pop()
        .push("$gte").add("n", "a").add("t", 1).pop()
        .pop().get();
    List<BasicDBObject> toUpsert = Arrays.asList(
        new BasicDBObject("_id", new BasicDBObject("n", "a").append("t", 1)),
        new BasicDBObject("_id", new BasicDBObject("n", "a").append("t", 2)),
        new BasicDBObject("_id", new BasicDBObject("n", "a").append("t", 3)),
        new BasicDBObject("_id", new BasicDBObject("n", "a").append("t", 11))
    );
    for (BasicDBObject dbo : toUpsert) {
      collection.update(dbo, ((BasicDBObject) dbo.copy()).append("foo", "bar"), true, false);
    }
    List<DBObject> results = collection.find(query).toArray();
    assertEquals(Arrays.<DBObject>asList(
        new BasicDBObject("_id", new BasicDBObject("n", "a").append("t", 1)).append("foo", "bar"),
        new BasicDBObject("_id", new BasicDBObject("n", "a").append("t", 2)).append("foo", "bar"),
        new BasicDBObject("_id", new BasicDBObject("n", "a").append("t", 3)).append("foo", "bar")
    ), results);
  }

  @Test
  public void testAnotherUpsert() {
    DBCollection collection = newCollection();
    BasicDBObjectBuilder queryBuilder = BasicDBObjectBuilder.start().push("_id").
        append("f", "ca").push("1").append("l", 2).pop().push("t").append("t", 11).pop().pop();
    DBObject query = queryBuilder.get();

    DBObject update = BasicDBObjectBuilder.start().push("$inc").append("n.!", 1).append("n.a.b:false", 1).pop().get();
    final WriteResult result = collection.update(query, update, true, false);
    assertThat(result).isNotNull();
    assertThat(result.isUpdateOfExisting()).isFalse();
    assertThat(result.getN()).isEqualTo(1);

    DBObject expected = queryBuilder.push("n").append("!", 1).push("a").append("b:false", 1).pop().pop().get();
    assertThat(collection.findOne()).isEqualTo(expected);
  }

  // TODO WDEL
//  @Test
//  public void testAuthentication() {
//    Assume.assumeFalse(fongoAsyncRule.isRealMongo());
//    DB fongoDB = fongoAsyncRule.getDB("testDB");
//    assertFalse(fongoDB.isAuthenticated());
//    // Once authenticated, fongoDB should be available to answer yes, whatever the credentials were.
//    assertTrue(fongoDB.authenticate("login", "password".toCharArray()));
//    assertTrue(fongoDB.isAuthenticated());
//  }

  @Test
  public void testUpsertOnIdWithPush() {
    DBCollection collection = newCollection();

    DBObject update1 = BasicDBObjectBuilder.start().push("$push")
        .push("c").append("a", 1).append("b", 2).pop().pop().get();

    DBObject update2 = BasicDBObjectBuilder.start().push("$push")
        .push("c").append("a", 3).append("b", 4).pop().pop().get();

    collection.update(new BasicDBObject("_id", 1), update1, true, false);
    collection.update(new BasicDBObject("_id", 1), update2, true, false);

    DBObject expected = new BasicDBObject("_id", 1).append("c", Util.list(
        new BasicDBObject("a", 1).append("b", 2),
        new BasicDBObject("a", 3).append("b", 4)));

    assertEquals(expected, collection.findOne(new BasicDBObject("c.a", 3).append("c.b", 4)));
  }

  @Test
  public void testUpsertWithEmbeddedQuery() {
    DBCollection collection = newCollection();

    DBObject update = BasicDBObjectBuilder.start().push("$set").append("a", 1).pop().get();

    collection.update(new BasicDBObject("_id", 1).append("e.i", 1), update, true, false);

    DBObject expected = BasicDBObjectBuilder.start().append("_id", 1).push("e").append("i", 1).pop().append("a", 1).get();

    assertEquals(expected, collection.findOne(new BasicDBObject("_id", 1)));
  }

  @Test
  public void testFindAndModifyReturnOld() {
    final DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("a", 1).append("b", new BasicDBObject("c", 1)));

    final BasicDBObject query = new BasicDBObject("_id", 1);
    final BasicDBObject update = new BasicDBObject("$inc", new BasicDBObject("a", 1).append("b.c", 1));
    final DBObject result = collection.findAndModify(query, null, null, false, update, false, false);

    assertEquals(new BasicDBObject("_id", 1).append("a", 1).append("b", new BasicDBObject("c", 1)), result);
    assertEquals(new BasicDBObject("_id", 1).append("a", 2).append("b", new BasicDBObject("c", 2)), collection.findOne());
  }

  @Test
  public void testFindAndModifyWithEmptyObjectProjection() {
    final DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("a", 1).append("b", new BasicDBObject("c", 1)));

    final BasicDBObject query = new BasicDBObject("_id", 1);
    final BasicDBObject update = new BasicDBObject("$unset", new BasicDBObject("b", ""));
    final DBObject result = collection.findAndModify(query, new BasicDBObject(), null, false, update, true, false);

    assertEquals(new BasicDBObject("_id", 1).append("a", 1), result);
  }

  /**
   * See issue #35
   */
  @Test
  public void findAndModify_with_projection_and_nothing_found() {
    final DBCollection collection = newCollection();

    final BasicDBObject query = new BasicDBObject("_id", 1);
    final BasicDBObject update = new BasicDBObject("$inc", new BasicDBObject("d", 1).append("b.c", 1));

    final DBObject result = collection.findAndModify(query, new BasicDBObject("e", true), null, false, update, true, false);

    Assertions.assertThat(result).isNull();
  }

  @Test
  public void testFindAndModifyWithInReturnOld() {
    final DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("a", 1).append("b", new BasicDBObject("c", 1)));

    final BasicDBObject query = new BasicDBObject("_id", new BasicDBObject("$in", Util.list(1, 2, 3)));
    final BasicDBObject update = new BasicDBObject("$inc", new BasicDBObject("a", 1).append("b.c", 1));
    final DBObject result = collection.findAndModify(query, null, null, false, update, false, false);

    assertEquals(new BasicDBObject("_id", 1).append("a", 1).append("b", new BasicDBObject("c", 1)), result);
    assertEquals(new BasicDBObject("_id", 1).append("a", 2).append("b", new BasicDBObject("c", 2)), collection.findOne());
  }

  @Test
  public void testFindAndModifyReturnNew() {
    final DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("a", 1).append("b", new BasicDBObject("c", 1)));

    final BasicDBObject query = new BasicDBObject("_id", 1);
    final BasicDBObject update = new BasicDBObject("$inc", new BasicDBObject("a", 1).append("b.c", 1));
    final DBObject result = collection.findAndModify(query, null, null, false, update, true, false);

    assertEquals(new BasicDBObject("_id", 1).append("a", 2).append("b", new BasicDBObject("c", 2)), result);
  }

  @Test
  public void testFindAndModifyUpsert() {
    DBCollection collection = newCollection();

    DBObject result = collection.findAndModify(new BasicDBObject("_id", 1),
        null, null, false, new BasicDBObject("$inc", new BasicDBObject("a", 1)), true, true);

    assertEquals(new BasicDBObject("_id", 1).append("a", 1), result);
    assertEquals(new BasicDBObject("_id", 1).append("a", 1), collection.findOne());
  }

  @Test
  public void testFindAndModifyUpsertReturnNewFalse() {
    DBCollection collection = newCollection();

    DBObject result = collection.findAndModify(new BasicDBObject("_id", 1),
        null, null, false, new BasicDBObject("$inc", new BasicDBObject("a", 1)), false, true);

    assertEquals(null, result);
    assertEquals(new BasicDBObject("_id", 1).append("a", 1), collection.findOne());
  }

  @Test
  public void testFindAndRemoveFromEmbeddedList() {
    DBCollection collection = newCollection();
    BasicDBObject obj = new BasicDBObject("_id", 1).append("a", Arrays.asList(1));
    collection.insert(obj);
    DBObject result = collection.findAndRemove(new BasicDBObject("_id", 1));
    assertEquals(obj, result);
  }

  @Test
  public void testFindAndRemoveNothingFound() {
    DBCollection coll = newCollection();
    assertNull("should return null if nothing was found", coll.findAndRemove(new BasicDBObject()));
  }

  @Test
  public void testFindAndModifyRemove() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("a", 1));
    DBObject result = collection.findAndModify(new BasicDBObject("_id", 1),
        null, null, true, null, false, false);

    assertEquals(new BasicDBObject("_id", 1).append("a", 1), result);
    assertEquals(null, collection.findOne());
  }

  @Test
  public void testRemove() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2));
    collection.insert(new BasicDBObject("_id", 3));
    collection.insert(new BasicDBObject("_id", 4));

    final WriteResult id = collection.remove(new BasicDBObject("_id", 2));

    assertEquals(null,
        collection.findOne(new BasicDBObject("_id", 2)));
    assertThat(id.getN()).isEqualTo(1);
    assertThat(id.getUpsertedId()).isNull();
  }

  @Test
  public void testConvertJavaListToDbList() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("n", Arrays.asList(1, 2)));
    DBObject result = collection.findOne();
    assertTrue("not a DBList", result.get("n") instanceof BasicDBList);
  }

  @Test
  public void testConvertJavaMapToDBObject() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("n", Collections.singletonMap("a", 1)));
    DBObject result = collection.findOne();
    assertTrue("not a DBObject", result.get("n") instanceof BasicDBObject);
  }

  @Test
  public void testDistinctQuery() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("n", 1).append("_id", 1));
    collection.insert(new BasicDBObject("n", 2).append("_id", 2));
    collection.insert(new BasicDBObject("n", 3).append("_id", 3));
    collection.insert(new BasicDBObject("n", 1).append("_id", 4));
    collection.insert(new BasicDBObject("n", 1).append("_id", 5));
    assertEquals(Arrays.asList(1, 2, 3), collection.distinct("n"));
  }

  @Test
  public void testDistinctHierarchicalQuery() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("n", new BasicDBObject("i", 1)).append("_id", 1));
    collection.insert(new BasicDBObject("n", new BasicDBObject("i", 2)).append("_id", 2));
    collection.insert(new BasicDBObject("n", new BasicDBObject("i", 3)).append("_id", 3));
    collection.insert(new BasicDBObject("n", new BasicDBObject("i", 1)).append("_id", 4));
    collection.insert(new BasicDBObject("n", new BasicDBObject("i", 1)).append("_id", 5));
    assertEquals(Arrays.asList(1, 2, 3), collection.distinct("n.i"));
  }

  @Test
  public void testDistinctHierarchicalQueryWithArray() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("n", new BasicDBObject("i", Util.list(1, 2, 3))).append("_id", 1));
    collection.insert(new BasicDBObject("n", new BasicDBObject("i", Util.list(3, 4))).append("_id", 2));
    collection.insert(new BasicDBObject("n", new BasicDBObject("i", Util.list(1, 5))).append("_id", 3));
    assertEquals(Arrays.asList(1, 2, 3, 4, 5), collection.distinct("n.i"));
  }

  @Test
  public void testSave() {
    DBCollection collection = newCollection();
    BasicDBObject inserted = new BasicDBObject("_id", 1);
    collection.insert(inserted);
    collection.save(inserted);

    assertThat(collection.find().toArray()).containsOnly(inserted);
  }

  @Test
  public void should_save_insert() {
    DBCollection collection = newCollection();
    BasicDBObject inserted = new BasicDBObject("_id", 1);
    collection.save(inserted);

    assertThat(collection.find().toArray()).containsOnly(inserted);
  }

  @Test(expected = DuplicateKeyException.class)
  public void testInsertDuplicateWithConcernThrows() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 1), WriteConcern.SAFE);
  }

  @Test(expected = DuplicateKeyException.class)
  public void testInsertDuplicateWithDefaultConcernOnMongo() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 1));
  }

  @Test
  public void testInsertDuplicateIgnored() {
    DBCollection collection = newCollection();
    collection.getDB().getMongo().setWriteConcern(WriteConcern.UNACKNOWLEDGED);
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 1));
    assertEquals(1, collection.count());
  }

  @Test
  public void testSortByEmbeddedKey() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("a", new BasicDBObject("b", 1)));
    collection.insert(new BasicDBObject("_id", 2).append("a", new BasicDBObject("b", 2)));
    collection.insert(new BasicDBObject("_id", 3).append("a", new BasicDBObject("b", 3)));
    List<DBObject> results = collection.find().sort(new BasicDBObject("a.b", -1)).toArray();
    assertEquals(
        Arrays.asList(
            new BasicDBObject("_id", 3).append("a", new BasicDBObject("b", 3)),
            new BasicDBObject("_id", 2).append("a", new BasicDBObject("b", 2)),
            new BasicDBObject("_id", 1).append("a", new BasicDBObject("b", 1))
        ), results
    );
  }

  @Test
  public void testCommandQuery() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("a", 3));
    collection.insert(new BasicDBObject("_id", 2).append("a", 2));
    collection.insert(new BasicDBObject("_id", 3).append("a", 1));

    assertEquals(
        new BasicDBObject("_id", 3).append("a", 1),
        collection.findOne(new BasicDBObject(), null, new BasicDBObject("a", 1))
    );
  }

  @Test
  public void testInsertReturnModifiedDocumentCount() {
    DBCollection collection = newCollection();
    WriteResult result = collection.insert(new BasicDBObject("_id", new BasicDBObject("foo", 1)), new BasicDBObject("_id", new BasicDBObject("foo", 2)));
    assertEquals(null, result.getUpsertedId());
    Assertions.assertThat(result.isUpdateOfExisting()).isFalse();

    // Don't know why, but "n" is "0" on mongodb 2.6.7...
    assertEquals(2, result.getN()); // 0 in real mongo, don't know why.
  }

  @Test
  public void testUpdateWithIdInMultiReturnModifiedDocumentCount() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1), new BasicDBObject("_id", 2));
    WriteResult result = collection.update(new BasicDBObject("_id", new BasicDBObject("$in", Arrays.asList(1, 2))),
        new BasicDBObject("$set", new BasicDBObject("n", 1)), false, true);
    assertEquals(2, result.getN());
  }

  @Test
  public void testUpdateWithObjectIdReturnModifiedDocumentCount() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", new BasicDBObject("n", 1)));
    DBObject query = new BasicDBObject("_id", new BasicDBObject("n", 1));
    DBObject update = new BasicDBObject("$set", new BasicDBObject("a", 1));
    WriteResult result = collection.update(query, update, false, false);
    assertEquals(1, result.getN());
  }

  /**
   * Test that ObjectId is getting generated even if _id is present in
   * DBObject but it's value is null
   *
   * @throws Exception
   */
  @Test
  public void testIdGenerated() throws Exception {
    DBObject toSave = new BasicDBObject();
    toSave.put("_id", null);
    toSave.put("name", "test");
    Fongo fongo = newFongo();
    DB fongoDB = fongo.getDB("testDB");
    DBCollection collection = fongoDB.getCollection("testCollection");
    collection.save(toSave);
    DBObject result = collection.findOne(new BasicDBObject("name", "test"));
    //default index in mongoDB
    final String ID_KEY = "_id";
    assertNotNull("Expected _id to be generated" + result.get(ID_KEY));
  }

  @Test
  public void testDropDatabaseAlsoDropsCollectionData() throws Exception {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject());
    collection.getDB().dropDatabase();
    assertEquals("Collection should have no data", 0, collection.count());
  }

  @Test
  public void testDropCollectionAlsoDropsFromDB() throws Exception {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject());
    collection.drop();
    assertEquals("Collection should have no data", 0.D, collection.count(), 0.D);
    assertFalse("Collection shouldn't exist in DB", collection.getDB().getCollectionNames().contains(collection.getName()));
  }

  @Test
  public void testDropDatabaseFromFongoDropsAllData() throws Exception {
    Fongo fongo = newFongo();
    DBCollection collection = fongo.getDB("db").getCollection("coll");
    collection.insert(new BasicDBObject());
    fongo.dropDatabase("db");
    assertEquals("Collection should have no data", 0.D, collection.count(), 0.D);
    assertFalse("Collection shouldn't exist in DB", collection.getDB().getCollectionNames().contains(collection.getName()));
    assertFalse("DB shouldn't exist in fongo", fongo.getDatabaseNames().contains("db"));
  }

  @Test
  public void testDropDatabaseFromFongoWithMultipleCollectionsDropsBothCollections() throws Exception {
    Fongo fongo = newFongo();
    DB db = fongo.getDB("db");
    DBCollection collection1 = db.getCollection("coll1");
    DBCollection collection2 = db.getCollection("coll2");
    db.dropDatabase();
    assertFalse("Collection 1 shouldn't exist in DB", db.collectionExists(collection1.getName()));
    assertFalse("Collection 2 shouldn't exist in DB", db.collectionExists(collection2.getName()));
    assertFalse("DB shouldn't exist in fongo", fongo.getDatabaseNames().contains("db"));
  }

  @Test
  public void testDropCollectionsFromGetCollectionNames() {
    DB db = fongoRule.getDB();
    db.getCollection("coll1").insert(new BasicDBObject());
    db.getCollection("coll2").insert(new BasicDBObject());
    int dropCount = 0;
    for (String name : db.getCollectionNames()) {
      if (!name.startsWith("system.")) {
        db.getCollection(name).drop();
        dropCount++;
      }
    }
    assertEquals("should drop two collections", 2, dropCount);
  }

  @Test
  public void testDropCollectionsPermitReuseOfDBCollection() throws Exception {
    DB db = fongoRule.getDB();
    int startingCollectionSize = db.getCollectionNames().size();
    DBCollection coll1 = db.getCollection("coll1");
    DBCollection coll2 = db.getCollection("coll2");
    Assertions.assertThat(db.getCollectionNames()).hasSize(startingCollectionSize);
    coll1.insert(new BasicDBObject("hello", "world"));
    coll2.insert(new BasicDBObject("hello", "world"));
    // <["coll1", "coll2", "system.indexes"]>
    Assertions.assertThat(db.getCollectionNames()).hasSize(startingCollectionSize + (fongoRule.mustContainsSystemIndexes() ? 3 : 2));

    // when
    coll1.drop();
    coll2.drop();
    //<["system.indexes"]>
    Assertions.assertThat(db.getCollectionNames()).hasSize(startingCollectionSize + (fongoRule.mustContainsSystemIndexes() ? 1 : 0));

    // Insert a value must create the collection.
    coll1.insert(new BasicDBObject("_id", 1));
    Assertions.assertThat(db.getCollectionNames()).hasSize(startingCollectionSize + (fongoRule.mustContainsSystemIndexes() ? 2 : 1));
  }

  @Test
  public void testToString() {
    new Fongo("test").getMongo().toString();
  }

  @Test
  public void testForceError() throws Exception {
    Fongo fongo = newFongo();
    DB db = fongo.getDB("db");
    CommandResult result = db.command("forceerror");
    assertEquals("ok should always be defined", 0.0, result.get("ok"));
    assertEquals("exception: forced error", result.get("errmsg"));
    assertEquals(10038, result.get("code"));
  }

  @Test
  public void testUndefinedCommand() throws Exception {
    DB db = fongoRule.getDB();
    CommandResult result = db.command("undefined");
    assertEquals("ok should always be defined", 0.0, result.get("ok"));
    Assertions.assertThat((String) result.get("errmsg")).contains("no such command: 'undefined', bad cmd: '");
  }

  @Test
  public void testCountCommand() throws Exception {
    DBObject countCmd = new BasicDBObject("count", "coll");
    DB db = fongoRule.getDB();
    DBCollection coll = db.getCollection("coll");
    coll.insert(new BasicDBObject());
    coll.insert(new BasicDBObject());
    CommandResult result = db.command(countCmd);
    assertEquals("The command should have been successful", 1.0, result.get("ok"));
    assertEquals("The count should be in the result", 2.0, result.get("n"));  // 2.0 with fongo, 2 with mongo
  }

  @Test
  public void testCountWithSkipLimitWithSort() {
    Fongo fongo = newFongo();
    DB db = fongo.getDB("db");
    DBCollection collection = db.getCollection("coll");
    collection.insert(new BasicDBObject("_id", 0).append("date", 5L));
    collection.insert(new BasicDBObject("_id", -1).append("date", 5L));
    collection.insert(new BasicDBObject("_id", 1).append("date", 5L).append("str", "1"));
    collection.insert(new BasicDBObject("_id", 2).append("date", 6L).append("str", "2"));
    collection.insert(new BasicDBObject("_id", 3).append("date", 7L).append("str", "3"));
    collection.insert(new BasicDBObject("_id", 4).append("date", 8L).append("str", "4"));

    QueryBuilder builder = new QueryBuilder().start("_id").greaterThanEquals(1).lessThanEquals(5).and("str").in(Arrays.asList("1", "2", "3", "4"));

    DBObject countCmd = new BasicDBObject("count", "coll").append("limit", 2).append("skip", 4).append("query", builder.get());
    CommandResult result = db.command(countCmd);
    // Without sort.
    assertEquals(0D, result.get("n"));
  }

  @Test
  public void testDbRefs() {
    Fongo fong = newFongo();
    DB db = fong.getDB("db");
    DBCollection coll1 = db.getCollection("coll");
    DBCollection coll2 = db.getCollection("coll2");
    final String coll2oid = "coll2id";
    BasicDBObject coll2doc = new BasicDBObject("_id", coll2oid);
    coll2.insert(coll2doc);
    coll1.insert(new BasicDBObject("ref", new DBRef("coll2", coll2oid)));

    DBRef ref = (DBRef) coll1.findOne().get("ref");
    assertEquals("coll2", ref.getCollectionName());
    assertEquals(coll2oid, ref.getId());
  }

  @Test
  public void testFindAllWithDBList() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("tags", Util.list("mongo", "javascript")));

    // When
    DBObject result = collection.findOne(new BasicDBObject("tags", new BasicDBObject("$all", Util.list("mongo", "javascript"))));

    // then
    assertEquals(new BasicDBObject("_id", 1).append("tags", Util.list("mongo", "javascript")), result);
  }

  @Test
  public void testFindAllWithList() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("tags", Util.list("mongo", "javascript")));

    // When
    DBObject result = collection.findOne(new BasicDBObject("tags", new BasicDBObject("$all", Arrays.asList("mongo", "javascript"))));

    // then
    assertEquals(new BasicDBObject("_id", 1).append("tags", Util.list("mongo", "javascript")), result);
  }

  @Test
  public void testFindAllWithCollection() throws Exception {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("tags", Util.list("mongo", "javascript")));

    // When
    DBObject result = collection.findOne(new BasicDBObject("tags", new BasicDBObject("$all", newHashSet("mongo", "javascript"))));

    // then
    assertEquals(new BasicDBObject("_id", 1).append("tags", Util.list("mongo", "javascript")), result);
  }

  @Test
  public void testEncodingHooks() {
    BSON.addEncodingHook(Seq.class, new Transformer() {
      @Override
      public Object transform(Object o) {
        return (o instanceof Seq) ? ((Seq) o).data : o;
      }
    });

    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2));

    DBObject result1 = collection.findOne(new BasicDBObject("_id", new BasicDBObject("$in", new Seq(1, 3))));
    assertEquals(new BasicDBObject("_id", 1), result1);

    DBObject result2 = collection.findOne(new BasicDBObject("_id", new BasicDBObject("$nin", new Seq(1, 3))));
    assertEquals(new BasicDBObject("_id", 2), result2);
  }

  @Test
  public void testModificationsOfResultShouldNotChangeStorage() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    DBObject result = collection.findOne();
    result.put("newkey", 1);
    assertEquals("should not have newkey", new BasicDBObject("_id", 1), collection.findOne());
  }

  @Test(timeout = 16000)
  public void testMultiThreadInsert() throws Exception {
    ch.qos.logback.classic.Logger LOG = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(FongoDBCollection.class);
    Level oldLevel = LOG.getLevel();
    try {
      LOG.setLevel(Level.ERROR);
      LOG = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ExpressionParser.class);
      LOG.setLevel(Level.ERROR);

      int size = 1000;
      final DBCollection col = new Fongo("InMemoryMongo").getDB("myDB").createCollection("myCollection", new BasicDBObject());

      final CountDownLatch lockSynchro = new CountDownLatch(size);
      final CountDownLatch lockDone = new CountDownLatch(size);
      for (int i = 0; i < size; i++) {
        new Thread() {
          public void run() {
            lockSynchro.countDown();
            col.insert(new BasicDBObject("multiple", 1), WriteConcern.ACKNOWLEDGED);
            lockDone.countDown();
          }
        }.start();
      }

      assertTrue("Too long :-(", lockDone.await(15, TimeUnit.SECONDS));

      // Count must be same value as size
      assertEquals(size, col.getCount());
    } finally {
      LOG.setLevel(oldLevel);
    }
  }

  // Don't know why, but request by _id only return document event if limit is set
  @Test
  public void testFindLimit0ById() throws Exception {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", "jon").append("name", "hoff"));
    List<DBObject> result = collection.find().limit(0).toArray();
    assertNotNull(result);
    assertEquals(1, result.size());
  }

  // NOT ANYMORE : Don't know why, but request by _id only return document even if skip is set
  @Test
  public void testFindSkip1yId() throws Exception {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", "jon").append("name", "hoff"));
    List<DBObject> result = collection.find(new BasicDBObject("_id", "jon")).skip(1).toArray();
    assertNotNull(result);
    assertEquals(0, result.size());
  }

  @Test
  public void testFindIdInSkip() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 4));
    collection.insert(new BasicDBObject("_id", 3));
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2));

    DBCursor cursor = collection.find(new BasicDBObject("_id",
        new BasicDBObject("$in", Arrays.asList(3, 2, 1)))).skip(3);
    assertEquals(Collections.emptyList(), cursor.toArray());
  }

  @Test
  public void testFindIdInLimit() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 4));
    collection.insert(new BasicDBObject("_id", 3));
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2));

    DBCursor cursor = collection.find(new BasicDBObject("_id",
        new BasicDBObject("$in", Arrays.asList(3, 2, 1)))).skip(1);
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 2),
        new BasicDBObject("_id", 3))
        , cursor.toArray());
  }

  @Test
  public void testWriteConcern() {
    assertNotNull(newFongo().getWriteConcern());
  }

  @Test
  public void shouldChangeWriteConcern() {
    Fongo fongo = newFongo();
    WriteConcern writeConcern = fongo.getMongo().getMongoClientOptions().getWriteConcern();
    assertEquals(writeConcern, fongo.getWriteConcern());
    assertTrue(writeConcern != WriteConcern.FSYNC_SAFE);

    // Change write concern
    fongo.getMongo().setWriteConcern(WriteConcern.FSYNC_SAFE);
    assertEquals(WriteConcern.FSYNC_SAFE, fongo.getWriteConcern());
  }

  // Id is always the first field.
  @Test
  public void shouldInsertIdFirst() throws Exception {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("date", 1).append("_id", new ObjectId()));
    collection.insert(new BasicDBObject("date", 2).append("_id", new ObjectId()));
    collection.insert(new BasicDBObject("date", 3).append("_id", new ObjectId()));

    //
    List<DBObject> result = collection.find().toArray();
    for (DBObject object : result) {
      // The _id field is always the first.
      assertEquals("_id", object.toMap().keySet().iterator().next());
    }
  }

  @Test
  public void shouldSearchGteInArray() throws Exception {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("a", Util.list(1, 2, 3)));
    collection.insert(new BasicDBObject("_id", 2).append("a", 2));

    // When
    List<DBObject> objects = collection.find(new BasicDBObject("a", new BasicDBObject("$gte", 2))).toArray();

    // Then
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 1).append("a", Util.list(1, 2, 3)),
        new BasicDBObject("_id", 2).append("a", 2)), objects);
  }

  // issue #78 $gte throws Exception on non-Comparable
  @Test
  public void shouldNotThrowsExceptionOnNonComparableGte() throws Exception {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("a", new BasicDBObject("b", 1).append("c", 1)));
    collection.insert(new BasicDBObject("_id", 2).append("a", 2));

    // When
    List<DBObject> objects = collection.find(new BasicDBObject("a", new BasicDBObject("$gte", 2))).toArray();

    // Then
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 2).append("a", 2)), objects);
  }

  // issue #78 $gte throws Exception on non-Comparable
  @Test
  public void shouldNotThrowsExceptionOnNonComparableLte() throws Exception {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("a", new BasicDBObject("b", 1).append("c", 1)));
    collection.insert(new BasicDBObject("_id", 2).append("a", 2));

    // When
    List<DBObject> objects = collection.find(new BasicDBObject("a", new BasicDBObject("$lte", 2))).toArray();

    // Then
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 2).append("a", 2)), objects);
  }

  // issue #78 $gte throws Exception on non-Comparable
  @Test
  public void shouldNotThrowsExceptionOnNonComparableGt() throws Exception {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("a", new BasicDBObject("b", 1).append("c", 1)));
    collection.insert(new BasicDBObject("_id", 2).append("a", 2));

    // When
    List<DBObject> objects = collection.find(new BasicDBObject("a", new BasicDBObject("$gt", 1))).toArray();

    // Then
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 2).append("a", 2)), objects);
  }

  // issue #78 $gte throws Exception on non-Comparable
  @Test
  public void shouldNotThrowsExceptionOnNonComparableLt() throws Exception {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("a", new BasicDBObject("b", 1).append("c", 1)));
    collection.insert(new BasicDBObject("_id", 2).append("a", 2));

    // When
    List<DBObject> objects = collection.find(new BasicDBObject("a", new BasicDBObject("$lt", 3))).toArray();

    // Then
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 2).append("a", 2)), objects);
  }

  //PR#82
  @Test
  public void testAddOrReplaceElementMustWorkWithDollarOperator() {

    DBCollection collection = newCollection();
    String random1 = UUID.randomUUID().toString();
    String random2 = UUID.randomUUID().toString();
    BasicDBList list1 = new BasicDBList();
    list1.add(new BasicDBObject("_id", random1).append("var3", "val31"));
    list1.add(new BasicDBObject("_id", random2).append("var3", "val32"));
    collection.insert(new BasicDBObject("_id", 1234).append("var1", "val1").append("parentObject",
        new BasicDBObject("var2", "val21").append("subObject", list1)));

    DBObject query = new BasicDBObject("_id", 1234).append("parentObject.subObject._id", random1);
    DBObject update = new BasicDBObject("$set", new BasicDBObject("parentObject.subObject.$",
        new BasicDBObject("_id", random1).append("var3", "val33")));

    BasicDBList list2 = new BasicDBList();
    list2.add(new BasicDBObject("_id", random1).append("var3", "val33"));
    list2.add(new BasicDBObject("_id", random2).append("var3", "val32"));
    DBObject expected = new BasicDBObject("_id", 1234).append("var1", "val1").append("parentObject",
        new BasicDBObject("var2", "val21").append("subObject", list2));

    collection.update(query, update);
    assertEquals(collection.findOne(new BasicDBObject("_id", 1234)), expected);
  }

  @Test
  public void shouldCompareObjectId() throws Exception {
    // Given
    DBCollection collection = newCollection();
    ObjectId id1 = ObjectId.get();
    ObjectId id2 = ObjectId.get();
    collection.insert(new BasicDBObject("_id", id1));
    collection.insert(new BasicDBObject("_id", id2));

    // When
    List<DBObject> objects = collection.find(new BasicDBObject("_id", new BasicDBObject("$gte", id1))).toArray();

    // Then
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", id1),
        new BasicDBObject("_id", id2)
    ), objects);
  }

  @Test
  public void canInsertWithNewObjectId() throws Exception {
    DBCollection collection = newCollection();
    ObjectId id = ObjectId.get();

    collection.insert(new BasicDBObject("_id", id).append("name", "jon"));

    assertEquals(1, collection.count(new BasicDBObject("name", "jon")));
  }

  @Test
  public void saveStringAsObjectId() throws Exception {
    DBCollection collection = newCollection();
    String id = ObjectId.get().toString();

    BasicDBObject object = new BasicDBObject("_id", id).append("name", "jon");
    collection.insert(object);

    assertEquals(1, collection.count(new BasicDBObject("name", "jon")));
    assertEquals(id, object.get("_id"));
  }

  // http://docs.mongodb.org/manual/reference/operator/type/
  @Test
  public void shouldFilterByType() throws Exception {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("date", 1).append("_id", 1));
    collection.insert(new BasicDBObject("date", 2D).append("_id", 2));
    collection.insert(new BasicDBObject("date", "3").append("_id", 3));
    ObjectId id = new ObjectId();
    collection.insert(new BasicDBObject("date", true).append("_id", id));
    collection.insert(new BasicDBObject("date", null).append("_id", 5));
    collection.insert(new BasicDBObject("date", 6L).append("_id", 6));
    collection.insert(new BasicDBObject("date", Util.list(1, 2, 3)).append("_id", 7));
    collection.insert(new BasicDBObject("date", Util.list(1D, 2L, "3", 4)).append("_id", 8));
    collection.insert(new BasicDBObject("date", Util.list(Util.list(1D, 2L, "3", 4))).append("_id", 9));
    collection.insert(new BasicDBObject("date", 2F).append("_id", 10));
    collection.insert(new BasicDBObject("date", new BasicDBObject("x", 1)).append("_id", 11));

    // When
    List<DBObject> objects = collection.find(new BasicDBObject("date", new BasicDBObject("$type", 1))).toArray();
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 2).append("date", 2D),
        new BasicDBObject("date", Util.list(1D, 2L, "3", 4)).append("_id", 8),
        new BasicDBObject("date", 2F).append("_id", 10)), objects);

    // When
    // String
    objects = collection.find(new BasicDBObject("date", new BasicDBObject("$type", 2))).toArray();
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 3).append("date", "3"),
        new BasicDBObject("date", Util.list(1D, 2L, "3", 4)).append("_id", 8)), objects);

    // Integer
    objects = collection.find(new BasicDBObject("date", new BasicDBObject("$type", 16))).toArray();
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 1).append("date", 1),
        new BasicDBObject("date", Util.list(1, 2, 3)).append("_id", 7),
        new BasicDBObject("date", Util.list(1D, 2L, "3", 4)).append("_id", 8)), objects);

    // ObjectId
    objects = collection.find(new BasicDBObject("_id", new BasicDBObject("$type", 7))).toArray();
    assertEquals(Collections.singletonList(new BasicDBObject("_id", id).append("date", true)), objects);

    // Boolean
    objects = collection.find(new BasicDBObject("date", new BasicDBObject("$type", 8))).toArray();
    assertEquals(Collections.singletonList(new BasicDBObject("_id", id).append("date", true)), objects);

    // Long ?
    objects = collection.find(new BasicDBObject("date", new BasicDBObject("$type", 18))).toArray();
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 6).append("date", 6L),
        new BasicDBObject("date", Util.list(1D, 2L, "3", 4)).append("_id", 8)), objects);

    // Array ?
    objects = collection.find(new BasicDBObject("date", new BasicDBObject("$type", 4))).toArray();
    assertEquals(Arrays.asList(
        new BasicDBObject("date", Util.list(Util.list(1D, 2L, "3", 4))).append("_id", 9)), objects);

    // Null ?
    objects = collection.find(new BasicDBObject("date", new BasicDBObject("$type", 10))).toArray();
    assertEquals(Collections.singletonList(new BasicDBObject("_id", 5).append("date", null)), objects);

    // Object ?
    objects = collection.find(new BasicDBObject("date", new BasicDBObject("$type", 3))).toArray();
    assertEquals(Arrays.asList(
//        new BasicDBObject("_id", 1).append("date", 1),
//        new BasicDBObject("_id", 2).append("date", 2D),
//        new BasicDBObject("_id", 3).append("date", "3"),
//        new BasicDBObject("_id", id).append("date", true),
//        new BasicDBObject("_id", 6).append("date", 6L),
//        new BasicDBObject("_id", 7).append("date", Util.list(1, 2, 3)),
//        new BasicDBObject("_id", 8).append("date", Util.list(1D, 2L, "3", 4)),
//        new BasicDBObject("_id", 9).append("date", Util.list(Util.list(1D, 2L, "3", 4))),
//        new BasicDBObject("_id", 10).append("date", 2F),
        new BasicDBObject("_id", 11).append("date", new BasicDBObject("x", 1))), objects);
  }

  // sorting like : http://docs.mongodb.org/manual/reference/operator/type/
  @Test
  public void testSorting() throws Exception {
    // Given
    DBCollection collection = newCollection();

    Date date = new Date();
    collection.insert(new BasicDBObject("_id", 1).append("x", 3));
    collection.insert(new BasicDBObject("_id", 2).append("x", 2.9D));
    collection.insert(new BasicDBObject("_id", 3).append("x", date));
    collection.insert(new BasicDBObject("_id", 4).append("x", true));
    collection.insert(new BasicDBObject("_id", 5).append("x", new MaxKey()));
    collection.insert(new BasicDBObject("_id", 6).append("x", new MinKey()));
    collection.insert(new BasicDBObject("_id", 7).append("x", false));
    collection.insert(new BasicDBObject("_id", 8).append("x", 2));
    collection.insert(new BasicDBObject("_id", 9).append("x", date.getTime() + 100));
    collection.insert(new BasicDBObject("_id", 10));

    // When
    List<DBObject> objects = collection.find().sort(new BasicDBObject("x", 1)).toArray();

    // Then
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 6).append("x", new MinKey()),
        new BasicDBObject("_id", 10),
        new BasicDBObject("_id", 8).append("x", 2),
        new BasicDBObject("_id", 2).append("x", 2.9D),
        new BasicDBObject("_id", 1).append("x", 3),
        new BasicDBObject("_id", 9).append("x", date.getTime() + 100),
        new BasicDBObject("_id", 7).append("x", false),
        new BasicDBObject("_id", 4).append("x", true),
        new BasicDBObject("_id", 3).append("x", date),
        new BasicDBObject("_id", 5).append("x", new MaxKey())
    ), objects);
  }

  @Test
  public void testSortingNull() throws Exception {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("x", new MinKey()));
    collection.insert(new BasicDBObject("_id", 2).append("x", new MaxKey()));
    collection.insert(new BasicDBObject("_id", 3).append("x", 3));
    collection.insert(new BasicDBObject("_id", 4).append("x", null));
    collection.insert(new BasicDBObject("_id", 5));
    collection.insert(new BasicDBObject("_id", 6).append("x", null));

    // When
    List<DBObject> objects = collection.find().sort(new BasicDBObject("x", 1)).toArray();

    // Then
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 1).append("x", new MinKey()),
        new BasicDBObject("_id", 4).append("x", null),
        new BasicDBObject("_id", 5),
        new BasicDBObject("_id", 6).append("x", null),
        new BasicDBObject("_id", 3).append("x", 3),
        new BasicDBObject("_id", 2).append("x", new MaxKey())
    ), objects);
  }

  // Pattern is last.
  @Test
  public void testSortingPattern() throws Exception {
    // Given
    DBCollection collection = newCollection();
//    DBCollection collection = new MongoClient().getDB("test").getCollection("sorting");
//    collection.drop();
    ObjectId id = ObjectId.get();
    Date date = new Date();
    collection.insert(new BasicDBObject("_id", 1).append("x", Pattern.compile("a*")));
    collection.insert(new BasicDBObject("_id", 2).append("x", 2));
    collection.insert(new BasicDBObject("_id", 3).append("x", "3"));
    collection.insert(new BasicDBObject("_id", 4).append("x", id));
    collection.insert(new BasicDBObject("_id", 5).append("x", new BasicDBObject("a", 3)));
    collection.insert(new BasicDBObject("_id", 6).append("x", date));
//    collection.insert(new BasicDBObject("_id", 7).append("x", "3".getBytes())); // later

    // When
    List<DBObject> objects = collection.find().sort(new BasicDBObject("x", 1)).toArray();

    // Then
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 2).append("x", 2),
        new BasicDBObject("_id", 3).append("x", "3"),
        new BasicDBObject("_id", 5).append("x", new BasicDBObject("a", 3)),
//        new BasicDBObject("_id", 7).append("x", "3".getBytes()), // later.
        new BasicDBObject("_id", 4).append("x", id),
        new BasicDBObject("_id", 6).append("x", date),
        new BasicDBObject("_id", 1).append("x", Pattern.compile("a*"))
    ), objects);
  }

  @Test
  public void testSortingDBObject() throws Exception {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("x", new BasicDBObject("a", 3)));
    ObjectId val = ObjectId.get();
    collection.insert(new BasicDBObject("_id", 2).append("x", val));
    collection.insert(new BasicDBObject("_id", 3).append("x", "3"));
    collection.insert(new BasicDBObject("_id", 4).append("x", 3));

    // When
    List<DBObject> objects = collection.find().sort(new BasicDBObject("x", 1)).toArray();

    // Then
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 4).append("x", 3),
        new BasicDBObject("_id", 3).append("x", "3"),
        new BasicDBObject("_id", 1).append("x", new BasicDBObject("a", 3)),
        new BasicDBObject("_id", 2).append("x", val)
    ), objects);
  }

  @Test
  public void testSortingNullVsMinKey() throws Exception {
    // Given
    DBCollection collection = newCollection();

    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2).append("x", new MinKey()));

    // When
    List<DBObject> objects = collection.find().sort(new BasicDBObject("x", 1)).toArray();

    // Then
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 2).append("x", new MinKey()),
        new BasicDBObject("_id", 1)
    ), objects);
  }

  // Previously on {@link ExpressionParserTest.
  @Test
  public void testStrangeSorting() throws Exception {
    // Given
    DBCollection collection = newCollection();

    collection.insert(new BasicDBObject("_id", 2).append("b", 1));
    collection.insert(new BasicDBObject("_id", 1).append("a", 3));

    // When
    List<DBObject> objects = collection.find().sort(new BasicDBObject("a", 1)).toArray();

    // Then
    assertEquals(Arrays.asList(
        new BasicDBObject("_id", 2).append("b", 1),
        new BasicDBObject("_id", 1).append("a", 3)
    ), objects);
  }

  /**
   * line 456 (LOG.debug("restrict with index {}, from {} to {} elements", matchingIndex.getName(), _idIndex.size(), dbObjectIterable.size());) obviously throws a null pointer if dbObjectIterable is null. This exact case is handled 4 lines below, but it does not apply to the log message. Please catch appropriately
   */
  @Test
  public void testIssue1() {
    ch.qos.logback.classic.Logger LOG = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(FongoDBCollection.class);
    Level oldLevel = LOG.getLevel();
    try {
      LOG.setLevel(Level.DEBUG);
      // Given
      DBCollection collection = newCollection();

      // When
      collection.remove(new BasicDBObject("_id", 1));

    } finally {
      LOG.setLevel(oldLevel);
    }
  }

  @Test
  public void testBinarySave() throws Exception {
    // Given
    DBCollection collection = newCollection();
    Binary expectedId = new Binary("friend2".getBytes());

    // When
    collection.save(BasicDBObjectBuilder.start().add("_id", expectedId).get());
    collection.update(BasicDBObjectBuilder.start().add("_id", new Binary("friend2".getBytes())).get(), new BasicDBObject("date", 12));

    // Then
    List<DBObject> result = collection.find().toArray();
    assertThat(result).hasSize(1);
    assertThat(result.get(0).keySet()).containsExactly("_id", "date");
    assertThat(result.get(0).get("_id")).isEqualTo("friend2".getBytes());
    assertThat(result.get(0).get("date")).isEqualTo(12);
  }

  @Test
  public void testBinarySaveWithBytes() throws Exception {
    // Given
    DBCollection collection = newCollection();
    Binary expectedId = new Binary("friend2".getBytes());

    // When
    collection.save(BasicDBObjectBuilder.start().add("_id", expectedId).get());
    collection.update(BasicDBObjectBuilder.start().add("_id", "friend2".getBytes()).get(), new BasicDBObject("date", 12));

    // Then
    List<DBObject> result = collection.find().toArray();
    assertThat(result).hasSize(1);
    assertThat(result.get(0).keySet()).containsExactly("_id", "date");
    assertThat(result.get(0).get("_id")).isEqualTo("friend2".getBytes());
    assertThat(result.get(0).get("date")).isEqualTo(12);
  }


  @Test
  public void testBinarySaveWithNestedByteArray() throws Exception {
    // Given
    DBCollection collection = newCollection();

    byte[] byteArray = "nestedByteArray".getBytes();
    DBObject innerField = BasicDBObjectBuilder.start().add("value", byteArray).get();

    // When
    collection.insert(BasicDBObjectBuilder.start().add("_id", 1).add("nested", innerField).get());

    // Then
    List<DBObject> result = collection.find().toArray();
    assertThat(result).hasSize(1);
    assertThat(result.get(0).keySet()).containsExactly("_id", "nested");
    assertThat(result.get(0).get("_id")).isEqualTo(1);
    assertThat(DBObject.class.isAssignableFrom(result.get(0).get("nested").getClass())).isTrue();
    assertThat(((DBObject) result.get(0).get("nested")).get("value")).isEqualTo("nestedByteArray".getBytes());
  }

  // can not change _id of a document query={ "_id" : "52986f667f6cc746624b0db5"}, document={ "name" : "Robert" , "_id" : { "$oid" : "52986f667f6cc746624b0db5"}}
  // See jongo SaveTest#canSaveWithObjectIdAsString
  @Test
  public void update_id_is_string_and_objectid() {
    // Given
    DBCollection collection = newCollection();
    ObjectId objectId = ObjectId.get();
    DBObject query = BasicDBObjectBuilder.start("_id", objectId.toString()).get();
    DBObject object = BasicDBObjectBuilder.start("_id", objectId).add("name", "Robert").get();
    // 16836  with real mongo
    expectWriteConcernException(exception, 16837);

    // When
    collection.update(query, object, true, false);
  }

  // See http://docs.mongodb.org/manual/reference/operator/projection/elemMatch/
  @Test
  public void projection_elemMatch() {
    // Given
    DBCollection collection = newCollection();
    this.fongoRule.insertJSON(collection, "[{\n"
        + " _id: 1,\n"
        + " zipcode: 63109,\n"
        + " students: [\n"
        + "              { name: \"john\"},\n"
        + "              { name: \"jess\"},\n"
        + "              { name: \"jeff\"}\n"
        + "           ]\n"
        + "}\n,"
        + "{\n"
        + " _id: 2,\n"
        + " zipcode: 63110,\n"
        + " students: [\n"
        + "              { name: \"ajax\"},\n"
        + "              { name: \"achilles\"}\n"
        + "           ]\n"
        + "}\n,"
        + "{\n"
        + " _id: 3,\n"
        + " zipcode: 63109,\n"
        + " students: [\n"
        + "              { name: \"ajax\"},\n"
        + "              { name: \"achilles\"}\n"
        + "           ]\n"
        + "}\n,"
        + "{\n"
        + " _id: 4,\n"
        + " zipcode: 63109,\n"
        + " students: [\n"
        + "              { name: \"barney\"}\n"
        + "           ]\n"
        + "}]\n");

    // When
    List<DBObject> result = collection.find(fongoRule.parseDBObject("{ zipcode: 63109 },\n"),
        fongoRule.parseDBObject("{ students: { $elemMatch: { name: \"achilles\" } } }")).toArray();

    // Then
    assertEquals(fongoRule.parseList("[{ \"_id\" : 1}, "
        + "{ \"_id\" : 3, \"students\" : [ { name: \"achilles\"} ] }, { \"_id\" : 4}]"), result);
  }

  @Test
  public void projection_elemMatchWithBigSubdocument() {
    // Given
    DBCollection collection = newCollection();
    this.fongoRule.insertJSON(collection, "[{\n" +
        " _id: 1,\n" +
        " zipcode: 63109,\n" +
        " students: [\n" +
        "              { name: \"john\", school: 102, age: 10 },\n" +
        "              { name: \"jess\", school: 102, age: 11 },\n" +
        "              { name: \"jeff\", school: 108, age: 15 }\n" +
        "           ]\n" +
        "}\n," +
        "{\n" +
        " _id: 2,\n" +
        " zipcode: 63110,\n" +
        " students: [\n" +
        "              { name: \"ajax\", school: 100, age: 7 },\n" +
        "              { name: \"achilles\", school: 100, age: 8 }\n" +
        "           ]\n" +
        "}\n," +
        "{\n" +
        " _id: 3,\n" +
        " zipcode: 63109,\n" +
        " students: [\n" +
        "              { name: \"ajax\", school: 100, age: 7 },\n" +
        "              { name: \"achilles\", school: 100, age: 8 }\n" +
        "           ]\n" +
        "}\n," +
        "{\n" +
        " _id: 4,\n" +
        " zipcode: 63109,\n" +
        " students: [\n" +
        "              { name: \"barney\", school: 102, age: 7 }\n" +
        "           ]\n" +
        "}]\n");


    // When
    List<DBObject> result = collection.find(fongoRule.parseDBObject("{ zipcode: 63109 }"),
        fongoRule.parseDBObject("{ students: { $elemMatch: { school: 102 } } }")).toArray();

    // Then
    assertEquals(fongoRule.parseList("[{ \"_id\" : 1, \"students\" : [ { \"name\" : \"john\", \"school\" : 102, \"age\" : 10 } ] },\n" +
        "{ \"_id\" : 3 },\n" +
        "{ \"_id\" : 4, \"students\" : [ { \"name\" : \"barney\", \"school\" : 102, \"age\" : 7 } ] }]"), result);
  }

  // See http://docs.mongodb.org/manual/reference/operator/query/elemMatch/
  @Test
//  @Ignore
  public void query_elemMatch() {
    // Given
    DBCollection collection = newCollection();
    this.fongoRule.insertJSON(collection, "[{\n" +
        " _id: 1,\n" +
        " zipcode: 63109,\n" +
        " students: [\n" +
        "              { name: \"john\", school: 102, age: 10 },\n" +
        "              { name: \"jess\", school: 102, age: 11 },\n" +
        "              { name: \"jeff\", school: 108, age: 15 }\n" +
        "           ]\n" +
        "}\n," +
        "{\n" +
        " _id: 2,\n" +
        " zipcode: 63110,\n" +
        " students: [\n" +
        "              { name: \"ajax\", school: 100, age: 7 },\n" +
        "              { name: \"achilles\", school: 100, age: 8 }\n" +
        "           ]\n" +
        "}\n," +
        "{\n" +
        " _id: 3,\n" +
        " zipcode: 63109,\n" +
        " students: [\n" +
        "              { name: \"ajax\", school: 100, age: 7 },\n" +
        "              { name: \"achilles\", school: 100, age: 8 }\n" +
        "           ]\n" +
        "}\n," +
        "{\n" +
        " _id: 4,\n" +
        " zipcode: 63109,\n" +
        " students: [\n" +
        "              { name: \"barney\", school: 102, age: 7 }\n" +
        "           ]\n" +
        "}]\n");


    // When
    List<DBObject> result = collection.find(fongoRule.parseDBObject("{ zipcode: 63109 },\n"
        + "{ students: { $elemMatch: { school: 102 } } }")).toArray();

    // Then
    assertEquals(fongoRule.parseList("[{ \"_id\" : 1 , \"zipcode\" : 63109 , \"students\" : " +
        "[ { \"name\" : \"john\" , \"school\" : 102 , \"age\" : 10} , { \"name\" : \"jess\" , \"school\" : 102 , \"age\" : 11} , " +
        "{ \"name\" : \"jeff\" , \"school\" : 108 , \"age\" : 15}]}, " +
        "{ \"_id\" : 3 , \"zipcode\" : 63109 , \"students\" : " +
        "[ { \"name\" : \"ajax\" , \"school\" : 100 , \"age\" : 7} , " +
        "{ \"name\" : \"achilles\" , \"school\" : 100 , \"age\" : 8}]}, " +
        "{ \"_id\" : 4 , \"zipcode\" : 63109 , \"students\" : " +
        "[ { \"name\" : \"barney\" , \"school\" : 102 , \"age\" : 7}]}]"), result);
  }

  /**
   * https://github.com/fakemongo/fongo/issues/104
   */
  @Test
  public void query_elemMatch_with_not() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(fongoRule.parseList("[{ \"_id\" : { \"$oid\" : \"55337db0c830ea4e35252b9a\"} , " +
        "\"_class\" : \"domain.Vacancy\" , " +
        "\"applications\" : [ { \"_id\" :  null  , \"messages\" : [ { \"_class\" : \"domain.FooMessage\"}]}]}]"));

    // When
    final DBCursor dbObjects = collection.find(fongoRule.parseDBObject("{ \"applications.messages\" : { \"$not\" : { \"$elemMatch\" : { \"$or\" : [ { \"_class\" : \"domain.FooMessage\"}]}}}}"));

    // Then
    assertThat(dbObjects.toArray()).isEqualTo(new ArrayList<DBObject>());
  }

  /**
   * https://github.com/fakemongo/fongo/issues/104
   */
  @Test
  public void query_elemMatch_with_not_found_the_entry() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(fongoRule.parseList("[{ \"_id\" : { \"$oid\" : \"55337db0c830ea4e35252b9a\"} , " +
        "\"_class\" : \"domain.Vacancy\" , " +
        "\"applications\" : [ { \"_id\" :  null  , \"messages\" : [ { \"_class\" : \"domain.FooMessage\"}]}]}]"));

    // When
    final DBCursor dbObjects = collection.find(fongoRule.parseDBObject("{ \"applications.messages\" : { \"$elemMatch\" : { \"$or\" : [ { \"_class\" : \"domain.FooMessage\"}]}}}"));

    // Then
    assertThat(dbObjects.toArray()).isEqualTo(fongoRule.parseList("[{ \"_id\" : { \"$oid\" : \"55337db0c830ea4e35252b9a\"} , " +
        "\"_class\" : \"domain.Vacancy\" , " +
        "\"applications\" : [ { \"_id\" :  null  , \"messages\" : [ { \"_class\" : \"domain.FooMessage\"}]}]}]"));
  }

  @Test
  public void find_and_modify_with_projection_old_object() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(fongoRule.parseDBObject("{ \"name\" : \"John\" , \"address\" : \"Jermin Street\"}"));

    // When
    DBObject result = collection.findAndModify(new BasicDBObject("name", "John"), new BasicDBObject("name", 1),
        null, false, new BasicDBObject("$set", new BasicDBObject("name", "Robert")), false, false);

    // Then
    assertThat(result.get("name")).isNotNull().isEqualTo("John");
    assertFalse("Bad Projection", result.containsField("address"));
  }

  @Test
  public void find_and_modify_with_projection_new_object() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(fongoRule.parseDBObject("{ \"name\" : \"John\" , \"address\" : \"Jermin Street\"}"));

    // When
    DBObject result = collection.findAndModify(new BasicDBObject("name", "John"), new BasicDBObject("name", 1),
        null, false, new BasicDBObject("$set", new BasicDBObject("name", "Robert")), true, false);

    // Then
    assertThat(result.get("name")).isNotNull().isEqualTo("Robert");
    assertFalse("Bad Projection", result.containsField("address"));
  }

  @Test
  public void find_and_modify_with_projection_new_object_upsert() {
    // Given
    DBCollection collection = newCollection();

    // When
    DBObject result = collection.findAndModify(new BasicDBObject("name", "Rob"), new BasicDBObject("name", 1),
        null, false, new BasicDBObject("$set", new BasicDBObject("name", "Robert")), true, true);

    // Then
    assertThat(result.get("name")).isNotNull().isEqualTo("Robert");
    assertFalse("Bad Projection", result.containsField("address"));
  }

  @Test
  public void find_with_maxScan() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(fongoRule.parseDBObject("{ \"name\" : \"John\" , \"address\" : \"Jermin Street\"}"));
    collection.insert(fongoRule.parseDBObject("{ \"name\" : \"Robert\" , \"address\" : \"Jermin Street\"}"));

    // When
    List<DBObject> objects = collection.find().addSpecial("$maxScan", 1).toArray();

    // Then
    assertThat(objects).hasSize(1);
  }

  // http://docs.mongodb.org/manual/reference/operator/query/mod/
  @Test
  public void mod_must_be_handled() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(fongoRule.parseList("[{ \"_id\" : 1, \"item\" : \"abc123\", \"qty\" : 0 },\n" +
        "{ \"_id\" : 2, \"item\" : \"xyz123\", \"qty\" : 5 },\n" +
        "{ \"_id\" : 3, \"item\" : \"ijk123\", \"qty\" : 12 }]"));

    // When
    List<DBObject> objects = collection.find(fongoRule.parseDBObject("{ qty: { $mod: [ 4, 0 ] } } ")).toArray();

    // Then
    assertThat(objects).isEqualTo(fongoRule.parseList("[{ \"_id\" : 1, \"item\" : \"abc123\", \"qty\" : 0 },\n" +
        "{ \"_id\" : 3, \"item\" : \"ijk123\", \"qty\" : 12 }]"));
  }

  // https://github.com/fakemongo/fongo/issues/37
  @Test
  public void mod_with_number_must_be_handled() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(fongoRule.parseList("[{ \"_id\" : 1, \"item\" : \"abc123\", \"qty\" : 0 },\n" +
        "{ \"_id\" : 2, \"item\" : \"xyz123\", \"qty\" : 5 },\n" +
        "{ \"_id\" : 3, \"item\" : \"ijk123\", \"qty\" : 12 }]"));

    // When
    List<DBObject> objects = collection.find(fongoRule.parseDBObject("{ qty: { $mod: [ 4., 0 ] } } ")).toArray();

    // Then
    assertThat(objects).isEqualTo(fongoRule.parseList("[{ \"_id\" : 1, \"item\" : \"abc123\", \"qty\" : 0 },\n" +
        "{ \"_id\" : 3, \"item\" : \"ijk123\", \"qty\" : 12 }]"));
  }

  // https://github.com/fakemongo/fongo/issues/36
  @Test
  public void should_$divide_in_group_work_well() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(fongoRule.parseDBObject("{_id:1, bar: 'bazz'}"));

    // When
    AggregationOutput result = collection
        .aggregate(fongoRule.parseList("[{ $project: { bla: {$divide: [4,2]} } }]"));

    // Then
//    System.out.println(Lists.newArrayList(result.results())); // { "_id" : { "$oid" : "5368e0f3cf5a47d5a22d7b75"}}
    Assertions.assertThat(result.results()).isEqualTo(fongoRule.parseList("[{ \"_id\" : 1 , \"bla\" : 2.0}]"));
  }

  // #44 https://github.com/fakemongo/fongo/issues/44
  @Test
  public void should_string_id_not_retrieve_objectId() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject());
    DBObject object = collection.findOne();
    ObjectId objectId = (ObjectId) object.get("_id");

    // When
    // Then
    Assertions.assertThat(collection.findOne(new BasicDBObject("_id", objectId))).isEqualTo(object);
    Assertions.assertThat(collection.findOne(new BasicDBObject("_id", objectId.toString()))).isNull();
  }

  @Test
  public void should_$max_int_insert() {
    long now = new Date().getTime();
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1)
        .append("a", 100)
        .append("b", 200)

        .append("x", -100.0)
        .append("y", 200.0)

        .append("later", new Date(now + 10000))
        .append("before", new Date(now - 10000))
    );

    // When
    collection
        .update(
            new BasicDBObject("_id", 1),
            new BasicDBObject("$max", new BasicDBObject()
                .append("a", 101)
                .append("b", 102)
                .append("c", 103)

                .append("x", 1.0)
                .append("y", 2.0)
                .append("z", -1.0)

                .append("later", new Date(now))
                .append("before", new Date(now))
                .append("new", new Date(now))
            ),
            true,
            true
        );

    // Then
    DBObject result = collection.findOne();
    Assertions.assertThat(result).isEqualTo(new BasicDBObject("_id", 1)
        .append("a", 101)
        .append("b", 200)
        .append("c", 103)

        .append("x", 1.0)
        .append("y", 200.0)
        .append("z", -1.0)

        .append("later", new Date(now + 10000))
        .append("before", new Date(now))
        .append("new", new Date(now)));
  }

  @Test
  public void should_$min_int_insert() {
    // Given
    long now = new Date().getTime();
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1)
        .append("a", 100)
        .append("b", 200)

        .append("x", -100.0)
        .append("y", 200.0)

        .append("later", new Date(now + 10000))
        .append("before", new Date(now - 10000))
    );

    // When
    collection
        .update(
            new BasicDBObject("_id", 1),
            new BasicDBObject("$min", new BasicDBObject()
                .append("a", 99)
                .append("b", 202)
                .append("c", 103)

                .append("x", -101.0)
                .append("y", 202.0)
                .append("z", -1.0)

                .append("later", new Date(now))
                .append("before", new Date(now))
                .append("new", new Date(now))
            ),
            true,
            true
        );

    // Then
    DBObject result = collection.findOne();
    Assertions.assertThat(result).isEqualTo(new BasicDBObject("_id", 1)
        .append("a", 99)
        .append("b", 200)

        .append("x", -101.0)
        .append("y", 200.0)

        .append("later", new Date(now))
        .append("before", new Date(now - 10000))

        .append("c", 103)
        .append("z", -1.0)
        .append("new", new Date(now)));
  }

  @Test
  public void should_$max_long_insert() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1)
        .append("b", 200)
    );

    // When
    collection
        .update(
            new BasicDBObject("_id", 1),
            new BasicDBObject("$max", new BasicDBObject()
                .append("b", Long.MAX_VALUE)
            ),
            true,
            true
        );

    // Then
    DBObject result = collection.findOne();
    Assertions.assertThat(result).isEqualTo(new BasicDBObject("_id", 1)
        .append("b", Long.MAX_VALUE)
    );
  }

  @Test
  public void test_multi_update_should_works_in_multi_with_no_$() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 100).append("hi", 1));

    // When
    collection.update(new BasicDBObject("fongo", "sucks"), new BasicDBObject("not", "upserted"), false, true);

    // Then
    assertThat(collection.find().toArray()).containsOnly(new BasicDBObject("_id", 100).append("hi", 1));
  }

  @Test
  public void test_bulk_update_must_start_with_id() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 100).append("hi", 1));

    // When
    BulkWriteOperation bulkWriteOperation = collection.initializeOrderedBulkOperation();
    bulkWriteOperation.find(new BasicDBObject("find", "me")).upsert().update(new BasicDBObject("_id", 1).append("foo", "bar").append("find", "me"));
    bulkWriteOperation.find(new BasicDBObject("cantFind", "me")).update(new BasicDBObject("not", "upserted"));
    bulkWriteOperation.find(new BasicDBObject("_id", 100)).update(new BasicDBObject("hi", 2));

    exception.expect(IllegalArgumentException.class);
    bulkWriteOperation.execute();
  }

  @Test
  public void test_bulk_update_throw_a_no_operation() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 100).append("hi", 1));

    // When
    exception.expect(IllegalArgumentException.class);
    exception.expectMessage("state should be: writes is not an empty list");
    BulkWriteOperation bulkWriteOperation = collection.initializeOrderedBulkOperation();
    bulkWriteOperation.execute();
  }

  @Test
  public void test_bulk_update_must_start_with_$() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 100).append("hi", 1));

    // When
    BulkWriteOperation bulkWriteOperation = collection.initializeOrderedBulkOperation();
    bulkWriteOperation.find(new BasicDBObject("_id", 100)).update(new BasicDBObject("hi", 2));
    exception.expect(IllegalArgumentException.class);
    exception.expectMessage("Invalid BSON field name hi");
    bulkWriteOperation.execute();
  }

  @Test
  public void test_bulk_update() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 100).append("hi", 1));

    // When
    collection.update(new BasicDBObject("fongo", "sucks"), new BasicDBObject("$set", new BasicDBObject("not", "upserted")), false, true);

    BulkWriteOperation bulkWriteOperation = collection.initializeOrderedBulkOperation();
    bulkWriteOperation.find(new BasicDBObject("_id", "200")).upsert().update(new BasicDBObject("$set", new BasicDBObject("foo", "bar").append("find", "me")));
    bulkWriteOperation.find(new BasicDBObject("cantFind", "me")).update(new BasicDBObject("$set", new BasicDBObject("not", "upserted")));
    bulkWriteOperation.find(new BasicDBObject("_id", 100)).update(new BasicDBObject("$set", new BasicDBObject("hi", 2)));
    BulkWriteResult bulkResult = bulkWriteOperation.execute();

    // Then
    assertEquals(1, bulkResult.getModifiedCount()); // 1 modified
    assertEquals(1, bulkResult.getUpserts().size()); // 1 upsert
    assertEquals(0, bulkResult.getInsertedCount()); // 0 inserted
    assertEquals(0, bulkResult.getRemovedCount()); // 0 removed

    DBObject result;
    result = collection.findOne(new BasicDBObject("_id", "200"));
    assertEquals(new BasicDBObject("_id", "200").append("find", "me").append("foo", "bar"), result);
    result = collection.findOne(new BasicDBObject("cantFind", "me"));
    assertNull(result);
    result = collection.findOne(new BasicDBObject("_id", 100));
    assertEquals(new BasicDBObject("_id", 100).append("hi", 2), result);
  }

  @Test
  public void test_bulk_insert() {
    // Given
    DBCollection collection = newCollection();

    // When
    DBObject o1 = new BasicDBObject("_id", 1).append("a", 1);
    DBObject o2 = new BasicDBObject("_id", 2).append("b", 2);
    DBObject o3 = new BasicDBObject("_id", 3).append("c", 3);
    BulkWriteOperation bulkWriteOperation = collection.initializeOrderedBulkOperation();
    bulkWriteOperation.insert(o1);
    bulkWriteOperation.insert(o2);
    bulkWriteOperation.insert(o3);
    BulkWriteResult bulkResult = bulkWriteOperation.execute();

    // Then
    assertEquals(0, bulkResult.getModifiedCount()); // 0 modified
    assertEquals(0, bulkResult.getUpserts().size()); // 0 upsert
    assertEquals(3, bulkResult.getInsertedCount()); // 3 inserted
    assertEquals(0, bulkResult.getRemovedCount()); // 0 removed
    Assertions.assertThat(bulkResult.isAcknowledged()).isTrue();

    List<DBObject> dbObjects = collection.find().toArray();
    assertEquals(dbObjects.size(), 3);
    assertEquals(dbObjects, Lists.newArrayList(o1, o2, o3));
  }

  // https://github.com/fakemongo/fongo/issues/134
  @Test
  public void test_bulk_insert_unacknowledged() {
    // Given
    DBCollection collection = newCollection();

    // When
    DBObject o1 = new BasicDBObject("_id", 1).append("a", 1);
    DBObject o2 = new BasicDBObject("_id", 2).append("b", 2);
    DBObject o3 = new BasicDBObject("_id", 3).append("c", 3);
    BulkWriteOperation bulkWriteOperation = collection.initializeOrderedBulkOperation();
    bulkWriteOperation.insert(o1);
    bulkWriteOperation.insert(o2);
    bulkWriteOperation.insert(o3);
    BulkWriteResult bulkResult = bulkWriteOperation.execute(WriteConcern.UNACKNOWLEDGED);

    // Then
    Assertions.assertThat(bulkResult.isAcknowledged()).isFalse();

    List<DBObject> dbObjects = collection.find().toArray();
    assertEquals(dbObjects.size(), 3);
    assertEquals(dbObjects, Lists.newArrayList(o1, o2, o3));
  }

  @Test
  public void test_bulk_remove() {
    // Given
    DBCollection collection = newCollection();
    DBObject o1 = new BasicDBObject("_id", 1).append("a", 1);
    DBObject o2 = new BasicDBObject("_id", 2).append("b", 2);
    DBObject o3 = new BasicDBObject("_id", 3).append("c", 3);
    collection.insert(o1, o2, o3);

    // When
    BulkWriteOperation bulkWriteOperation = collection.initializeOrderedBulkOperation();
    bulkWriteOperation.find(o1).remove();
    bulkWriteOperation.find(new BasicDBObject("x", "y")).remove();
    bulkWriteOperation.find(o3).remove();
    BulkWriteResult bulkResult = bulkWriteOperation.execute();

    // Then
    assertEquals(0, bulkResult.getModifiedCount()); // 0 modified
    assertEquals(0, bulkResult.getUpserts().size()); // 0 upsert
    assertEquals(0, bulkResult.getInsertedCount()); // 0 inserted
    assertEquals(2, bulkResult.getRemovedCount()); // 2 removed

    List<DBObject> dbObjects = collection.find().toArray();
    assertEquals(dbObjects.size(), 1);
    assertEquals(dbObjects, Lists.newArrayList(o2));
  }


  @Test
  public void should_setOnInsert_insert_value() {
    // Given
    DBCollection collection = newCollection();
    ObjectId objectId = ObjectId.get();
    // When
    collection
        .update(
            new BasicDBObject(),
            new BasicDBObject()
                .append("$setOnInsert", new BasicDBObject("insertedAttr", "insertedValue"))
                .append("$set", new BasicDBObject("updatedAttr", "updatedValue").append("_id", objectId)),
            true,
            true
        );

    // Then
    Assertions.assertThat(collection.findOne(new BasicDBObject("_id", objectId))).isEqualTo(new BasicDBObject("_id", objectId)
        .append("insertedAttr", "insertedValue")
        .append("updatedAttr", "updatedValue"));
  }

  @Test
  public void should_setOnInsert_insert_value_only_one() {
    // Given
    DBCollection collection = newCollection();
    ObjectId objectId = ObjectId.get();
    // When
    collection
        .update(
            new BasicDBObject(),
            new BasicDBObject()
                .append("$setOnInsert", new BasicDBObject("insertedAttr", "insertedValue"))
                .append("$set", new BasicDBObject("updatedAttr", "updatedValue").append("_id", objectId)),
            true,
            true
        );
    collection
        .update(
            new BasicDBObject("_id", objectId),
            new BasicDBObject()
                .append("$setOnInsert", new BasicDBObject("insertedAttr", "insertedValue2"))
                .append("$set", new BasicDBObject("updatedAttr", "updatedValue2")),
            true,
            true
        );

    // Then
    Assertions.assertThat(collection.findOne(new BasicDBObject("_id", objectId))).isEqualTo(new BasicDBObject("_id", objectId)
        .append("insertedAttr", "insertedValue")
        .append("updatedAttr", "updatedValue2"));
  }

  @Test
  public void should_setOnInsert_insert_value_on_composite_fields() {
    // Given
    DBCollection collection = newCollection();

    DBObject insertedValues = new BasicDBObject("insertedAttr1", "insertedValue1").append("data.insertedAttr2", "insertedValue2");

    DBObject updatedValues = new BasicDBObject("updatedAttr1", "updatedValue1").append("data.updatedAttr2", "updatedValue2");

    collection.update(
        new BasicDBObject(),
        new BasicDBObject()
            .append("$setOnInsert", insertedValues)
            .append("$set", updatedValues),
        true,
        true
    );

    DBObject obj = collection.findOne();
    // { "_id" : { "$oid" : "53bd59cd7c2e6dc6f98160e0"} , "insertedAttr1" : "insertedValue1" , "data" : { "insertedAttr2" : "insertedValue2" , "updatedAttr2" : "updatedValue2"} , "updatedAttr1" : "updatedValue1"}
    Assertions.assertThat(obj.get("insertedAttr1")).isEqualTo("insertedValue1");
    Assertions.assertThat(obj.get("updatedAttr1")).isEqualTo("updatedValue1");

    DBObject data = (DBObject) obj.get("data");
    Assertions.assertThat(data.get("insertedAttr2")).isEqualTo("insertedValue2");
    Assertions.assertThat(data.get("updatedAttr2")).isEqualTo("updatedValue2");
  }

  @Test
  public void should_not_setOnInsert_insert_value_on_composite_fields() {
    // Given
    DBCollection collection = newCollection();

    DBObject insertedValues = new BasicDBObject("insertedAttr1", "insertedValue1").append("data.insertedAttr2", "insertedValue2");

    DBObject updatedValues = new BasicDBObject("updatedAttr1", "updatedValue1").append("data.updatedAttr2", "updatedValue2");

    ObjectId objectId = ObjectId.get();
    collection.update(
        new BasicDBObject("_id", objectId),
        new BasicDBObject()
            .append("$set", updatedValues),
        true,
        true
    );

    collection.update(
        new BasicDBObject("_id", objectId),
        new BasicDBObject()
            .append("$setOnInsert", insertedValues)
            .append("$set", updatedValues),
        true,
        true
    );

    DBObject obj = collection.findOne();
    // { "_id" : { "$oid" : "53bd59cd7c2e6dc6f98160e0"} , "insertedAttr1" : "insertedValue1" , "data" : { "insertedAttr2" : "insertedValue2" , "updatedAttr2" : "updatedValue2"} , "updatedAttr1" : "updatedValue1"}
    Assertions.assertThat(obj.get("insertedAttr1")).isNull();
    Assertions.assertThat(obj.get("updatedAttr1")).isEqualTo("updatedValue1");

    DBObject data = (DBObject) obj.get("data");
    Assertions.assertThat(data).isEqualTo(new BasicDBObject("updatedAttr2", "updatedValue2"));
  }

  @Test
  public void should_mul_multiply_values() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(fongoRule.parseDBObject("{ _id: 1, item: \"ABC\", price: 10.99 }\n"));

    // When
    collection.update(new BasicDBObject("_id", 1), fongoRule.parseDBObject("{ $mul: { price: 1.25 } }"));

    // Then
    Assertions.assertThat(collection.findOne(new BasicDBObject("_id", 1))).isEqualTo(new BasicDBObject("_id", 1)
        .append("item", "ABC")
        .append("price", 13.7375D));
  }

  @Test
  public void should_mul_add_field_if_not_exist() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(fongoRule.parseDBObject("{ _id: 2, item: \"Unknown\"}\n"));

    // When
    collection.update(new BasicDBObject("_id", 2), new BasicDBObject("$mul", new BasicDBObject("price", 100L)));

    // Then
    Assertions.assertThat(collection.findOne(new BasicDBObject("_id", 2))).isEqualTo(new BasicDBObject("_id", 2)
        .append("item", "Unknown")
        .append("price", 0L));
  }

  @Test
  public void should_mul_with_mixed_types_handle_long_as_result() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 3).append("item", "XYZ").append("price", 10L));

    // When
    collection.update(new BasicDBObject("_id", 3), new BasicDBObject("$mul", new BasicDBObject("price", 5)));

    // Then
    Assertions.assertThat(collection.findOne(new BasicDBObject("_id", 3))).isEqualTo(new BasicDBObject("_id", 3)
        .append("item", "XYZ")
        .append("price", 50L));
  }

  // https://github.com/fakemongo/fongo/issues/61
  @Test
  public void should_be_able_to_query_array_elements() {
    // Given
    DBCollection collection = newCollection();
    BasicDBList array = new BasicDBList();
    array.add(0);
    array.add(1);
    collection.insert(new BasicDBObject("_id", 1).append("items", array));

    // When
    DBObject result = collection.findOne(new BasicDBObject("items", 0));

    // Then
    Assertions.assertThat(result).isEqualTo(new BasicDBObject("_id", 1).append("items", array));
  }

  @Test
  public void should_not_$min_update_document() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("price", 10));

    // When
    collection.update(new BasicDBObject("_id", 1), new BasicDBObject("$min", new BasicDBObject("price", 11)));

    // Then
    DBObject result = collection.findOne(new BasicDBObject("_id", 1));
    Assertions.assertThat(result).isEqualTo(new BasicDBObject("_id", 1).append("price", 10));
  }

  @Test
  public void should_$min_update_document() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("price", 10));

    // When
    collection.update(new BasicDBObject("_id", 1), new BasicDBObject("$min", new BasicDBObject("price", 9)));

    // Then
    DBObject result = collection.findOne(new BasicDBObject("_id", 1));
    Assertions.assertThat(result).isEqualTo(new BasicDBObject("_id", 1).append("price", 9));
  }

  @Test
  public void should_not_$max_update_document() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("price", 10));

    // When
    collection.update(new BasicDBObject("_id", 1), new BasicDBObject("$max", new BasicDBObject("price", 9)));

    // Then
    DBObject result = collection.findOne(new BasicDBObject("_id", 1));
    Assertions.assertThat(result).isEqualTo(new BasicDBObject("_id", 1).append("price", 10));
  }

  @Test
  public void should_$max_update_document() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("price", 10));

    // When
    collection.update(new BasicDBObject("_id", 1), new BasicDBObject("$max", new BasicDBObject("price", 12)));

    // Then
    DBObject result = collection.findOne(new BasicDBObject("_id", 1));
    Assertions.assertThat(result).isEqualTo(new BasicDBObject("_id", 1).append("price", 12));
  }

  @Test
  public void should_fsync_return_value() {
    // Given

    // When
    final CommandResult fsync = fongoRule.getDB().getMongo().fsync(true);

    // Then
    Assertions.assertThat(fsync.get("ok")).isEqualTo(1.0);
  }

  @Test
  public void should_projection_$slice_return_simple_count() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("array", Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)));

    // When
    List<DBObject> dbObjects = collection.find(new BasicDBObject(), new BasicDBObject("array", new BasicDBObject("$slice", 3))).toArray();

    // Then
    Assertions.assertThat(dbObjects).isEqualTo(Arrays.asList(new BasicDBObject("_id", 1).append("array", Arrays.asList(1, 2, 3))));
  }

  @Test
  public void should_projection_$slice_return_last_elements() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("array", Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)));

    // When
    List<DBObject> dbObjects = collection.find(new BasicDBObject(), new BasicDBObject("array", new BasicDBObject("$slice", -3))).toArray();

    // Then
    Assertions.assertThat(dbObjects).isEqualTo(Arrays.asList(new BasicDBObject("_id", 1).append("array", Arrays.asList(8, 9, 10))));
  }

  @Test
  public void should_projection_$slice_return_sub() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("array", Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)));

    // When
    BasicDBList slice = new BasicDBList();
    slice.add(3);
    slice.add(5);
    List<DBObject> dbObjects = collection.find(new BasicDBObject(), new BasicDBObject("array", new BasicDBObject("$slice", slice))).toArray();

    // Then
    Assertions.assertThat(dbObjects).isEqualTo(Arrays.asList(new BasicDBObject("_id", 1).append("array", Arrays.asList(4, 5, 6, 7, 8))));
  }

  @Test
  public void should_projection_$slice_return_empty_sub() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("array", Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)));

    // When
    BasicDBList slice = new BasicDBList();
    slice.add(10);
    slice.add(5);
    List<DBObject> dbObjects = collection.find(new BasicDBObject(), new BasicDBObject("array", new BasicDBObject("$slice", slice))).toArray();

    // Then
    Assertions.assertThat(dbObjects).isEqualTo(Arrays.asList(new BasicDBObject("_id", 1).append("array", Arrays.asList())));
  }

  @Test(expected = MongoException.class)
  public void should_projection_$slice_return_empty_sub_if_limit_neg() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("array", Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)));

    // When
    BasicDBList slice = new BasicDBList();
    slice.add(10);
    slice.add(-5);
    List<DBObject> dbObjects = collection.find(new BasicDBObject(), new BasicDBObject("array", new BasicDBObject("$slice", slice))).toArray();

    // Then
  }

  @Test
  public void should_projection_$slice_return_last_sub() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("array", Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)));

    // When
    BasicDBList slice = new BasicDBList();
    slice.add(-7);
    slice.add(5);
    List<DBObject> dbObjects = collection.find(new BasicDBObject(), new BasicDBObject("array", new BasicDBObject("$slice", slice))).toArray();

    // Then
    Assertions.assertThat(dbObjects).isEqualTo(Arrays.asList(new BasicDBObject("_id", 1).append("array", Arrays.asList(4, 5, 6, 7, 8))));
  }

  @Test
  public void should_not_handle_timestamp() {
    // TODO !
//    Assume.assumeTrue(fongoRule.isRealMongo());
    // Given
    DBCollection collection = newCollection();
    final long now = 1444444444444L;
    collection.insert(new BasicDBObject("_id", 1).append("date", new Timestamp(now)));

    // Then
    Assertions.assertThat(collection.find().toArray()).containsExactly(new BasicDBObject("_id", 1).append("date", new Date(now)));
  }

  @Test
  public void should_not_handle_time() {
    // TODO !
//    Assume.assumeTrue(fongoRule.isRealMongo());
    // Given
    DBCollection collection = newCollection();
    final long now = 1444444444444L;
    collection.insert(new BasicDBObject("_id", 3).append("date", new Time(now)));

    // Then
    Assertions.assertThat(collection.find().toArray()).containsExactly(new BasicDBObject("_id", 3).append("date", new Date(now)));
  }

  @Test
  public void should_not_handle_character() {
    // TODO !
//    Assume.assumeTrue(fongoRule.isRealMongo());
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 2).append("value", Character.valueOf('c')));

    // Then
    Assertions.assertThat(collection.find().toArray()).containsExactly(new BasicDBObject("_id", 2).append("value", "c"));
  }

  @Test
  public void should_mixed_data_string_works_together() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("value", 'c'));
    collection.insert(new BasicDBObject("_id", 2).append("value", Character.valueOf('c')));
    collection.insert(new BasicDBObject("_id", 3).append("value", "c"));

    // When
    List<DBObject> dbObjects = collection.find(new BasicDBObject("value", "c")).sort(new BasicDBObject("_id", 1)).toArray();

    // Then
    Assertions.assertThat(dbObjects).isEqualTo(Arrays.asList(new BasicDBObject("_id", 1).append("value", "c"), new BasicDBObject("_id", 2).append("value", "c"), new BasicDBObject("_id", 3).append("value", "c")));
  }

  @Test
  public void should_mixed_data_integer_works_together() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("value", Integer.valueOf(100)));
    collection.insert(new BasicDBObject("_id", 2).append("value", new AtomicInteger(100)));
    collection.insert(new BasicDBObject("_id", 3).append("value", Byte.valueOf((byte) 100)));
    collection.insert(new BasicDBObject("_id", 4).append("value", Short.valueOf((short) 100)));
    collection.insert(new BasicDBObject("_id", 5).append("value", Long.valueOf(100)));
    collection.insert(new BasicDBObject("_id", 6).append("value", Float.valueOf(100)));
    collection.insert(new BasicDBObject("_id", 7).append("value", Double.valueOf(100)));
    collection.insert(new BasicDBObject("_id", 8).append("value", new AtomicLong(100)));

    // When
    List<DBObject> dbObjects = collection.find(new BasicDBObject("value", 100)).sort(new BasicDBObject("_id", 1)).toArray();

    // Then
    Assertions.assertThat(dbObjects).isEqualTo(Arrays.asList(new BasicDBObject("_id", 1).append("value", 100), new BasicDBObject("_id", 2).append("value", 100), new BasicDBObject("_id", 3).append("value", 100), new BasicDBObject("_id", 4).append("value", 100), new BasicDBObject("_id", 5).append("value", 100L), new BasicDBObject("_id", 6).append("value", 100D), new BasicDBObject("_id", 7).append("value", 100D), new BasicDBObject("_id", 8).append("value", 100L)));
  }

  @Test
  public void should_mixed_data_double_works_together() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("value", Integer.valueOf(100)));
    collection.insert(new BasicDBObject("_id", 2).append("value", new AtomicInteger(100)));
    collection.insert(new BasicDBObject("_id", 3).append("value", Byte.valueOf((byte) 100)));
    collection.insert(new BasicDBObject("_id", 4).append("value", Short.valueOf((short) 100)));
    collection.insert(new BasicDBObject("_id", 5).append("value", Long.valueOf(100)));
    collection.insert(new BasicDBObject("_id", 6).append("value", Float.valueOf(100)));
    collection.insert(new BasicDBObject("_id", 7).append("value", Double.valueOf(100)));
    collection.insert(new BasicDBObject("_id", 8).append("value", new AtomicLong(100)));

    // When
    List<DBObject> dbObjects = collection.find(new BasicDBObject("value", 100F)).sort(new BasicDBObject("_id", 1)).toArray();

    // Then
    Assertions.assertThat(dbObjects).isEqualTo(Arrays.asList(new BasicDBObject("_id", 1).append("value", 100), new BasicDBObject("_id", 2).append("value", 100), new BasicDBObject("_id", 3).append("value", 100), new BasicDBObject("_id", 4).append("value", 100), new BasicDBObject("_id", 5).append("value", 100L), new BasicDBObject("_id", 6).append("value", 100D), new BasicDBObject("_id", 7).append("value", 100D), new BasicDBObject("_id", 8).append("value", 100L)));
  }

  @Test
  public void should_mixed_data_long_works_together() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("value", Integer.valueOf(100)));
    collection.insert(new BasicDBObject("_id", 2).append("value", new AtomicInteger(100)));
    collection.insert(new BasicDBObject("_id", 3).append("value", Byte.valueOf((byte) 100)));
    collection.insert(new BasicDBObject("_id", 4).append("value", Short.valueOf((short) 100)));
    collection.insert(new BasicDBObject("_id", 5).append("value", Long.valueOf(100)));
    collection.insert(new BasicDBObject("_id", 6).append("value", Float.valueOf(100)));
    collection.insert(new BasicDBObject("_id", 7).append("value", Double.valueOf(100)));
    collection.insert(new BasicDBObject("_id", 8).append("value", new AtomicLong(100)));

    // When
    List<DBObject> dbObjects = collection.find(new BasicDBObject("value", 100L)).sort(new BasicDBObject("_id", 1)).toArray();

    // Then
    Assertions.assertThat(dbObjects).isEqualTo(Arrays.asList(new BasicDBObject("_id", 1).append("value", 100), new BasicDBObject("_id", 2).append("value", 100), new BasicDBObject("_id", 3).append("value", 100), new BasicDBObject("_id", 4).append("value", 100), new BasicDBObject("_id", 5).append("value", 100L), new BasicDBObject("_id", 6).append("value", 100D), new BasicDBObject("_id", 7).append("value", 100D), new BasicDBObject("_id", 8).append("value", 100L)));
  }

  @Test
  public void should_utf8_works() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", "ꂁ瞹\u243C\uEDCF鞯థ䭫蘇妙\u0010"));

    // When
    List<DBObject> dbObjects = collection.find().toArray();

    // Then
    Assertions.assertThat(dbObjects).isEqualTo(Arrays.asList(new BasicDBObject("_id", "ꂁ瞹\u243C\uEDCF鞯థ䭫蘇妙\u0010")));
  }

  @Test
  public void should_not_get_upsertedId_in_UNACKNOWLEDGED_write_concern() {
    // Given
    DBCollection collection = newCollection();
    collection.setWriteConcern(WriteConcern.UNACKNOWLEDGED);

    // When
    final WriteResult writeResult = collection.insert(new BasicDBObject("_id", "ꂁ瞹\u243C\uEDCF鞯థ䭫蘇妙\u0010"));

    // Then
    exception.expect(UnsupportedOperationException.class);
    writeResult.getUpsertedId();
  }

  /**
   * see https://github.com/fakemongo/fongo/issues/110
   */
  @Test
  public void should_$and_on_an_array_throw_exception() {
    // Given
    DBCollection collection = newCollection();

    DBObject[] sub = new DBObject[0];
    DBObject query = QueryBuilder.start().and(sub).get();
    ExpectedMongoException.expectMongoCommandException(exception, 2);
    exception.expectMessage("$and/$or/$nor must be a nonempty array");

    // When
    collection.count(query);

    // Then
//    assertThat(count).isEqualTo(0);
  }

  /**
   * see https://github.com/fakemongo/fongo/issues/110
   */
  @Test
  public void should_$or_on_an_array_throw_exception() {
    // Given
    DBCollection collection = newCollection();

    DBObject[] sub = new DBObject[0];
    DBObject query = QueryBuilder.start().or(sub).get();
    ExpectedMongoException.expectMongoCommandException(exception, 2);
    exception.expectMessage("$and/$or/$nor must be a nonempty array");

    // When
    collection.count(query);

    // Then
//    assertThat(count).isEqualTo(0);
  }

  /**
   * see https://github.com/fakemongo/fongo/issues/110
   */
  @Test
  public void should_$nor_on_an_array_throw_exception() {
    // Given
    DBCollection collection = newCollection();

    DBObject[] sub = new DBObject[0];
    DBObject query = new BasicDBObject("$nor", sub);
    ExpectedMongoException.expectMongoCommandException(exception, 2);
    exception.expectMessage("$and/$or/$nor must be a nonempty array");

    // When
    collection.count(query);

    // Then
//    assertThat(count).isEqualTo(0);
  }

  /**
   * see https://github.com/fakemongo/fongo/issues/122
   */
  @Test
  public void should_close_works() {
    newFongo().getMongo().close();
  }

  /**
   * see https://github.com/fakemongo/fongo/issues/125
   */
  @Test
  public void should_rename_a_collection() {
    // Given
    DBCollection collection = fongoRule.newCollection("db");
    collection.insert(new BasicDBObject("_id", 1));
    collection.createIndex(new BasicDBObject("date", 1));

    // When
    collection.rename("second");
    DBCollection second = collection.getDB().getCollection("second");

    // Then
    assertThat(second.getName()).isEqualTo("second");
    assertThat(second.find().toArray()).containsExactly(new BasicDBObject("_id", 1));
    assertThat(second.getIndexInfo()).hasSize(2);
    assertThat(collection.getName()).isEqualTo("db");
    assertThat(collection.getDB().getCollection("db")).isEqualTo(collection);
    assertThat(collection.getDB().getCollection("db").find().toArray()).isEmpty();
    if (fongoRule.isRealMongo()) {
      assertThat(collection.getDB().getCollection("db").getIndexInfo()).isEmpty();
    }
  }

  @Test
  public void testFindAllWithEmptyList() {
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1).append("tags", Util.list("mongo", "javascript")));

    // When
    DBObject result = collection.findOne(new BasicDBObject("tags", new BasicDBObject("$all", Arrays.asList())));

    // then
    assertNull(result);
  }

  // See https://github.com/fakemongo/fongo/issues/166
  @Test
  public void can_compare_uuid() {
    // Given
    DBCollection collection = fongoRule.newCollection("db");
    final UUID uuid = UUID.randomUUID();
    collection.insert(new BasicDBObject("_id", uuid));

    // When
    final List<DBObject> dbObjects = collection.find(new BasicDBObject("_id", "some string")).toArray();

    // Then
    assertThat(dbObjects).isEmpty();
  }

  // See https://github.com/fakemongo/fongo/issues/166
  @Test
  public void should_search_uuid() {
    // Given
    DBCollection collection = fongoRule.newCollection("db");
    final UUID uuid = UUID.randomUUID();
    collection.insert(new BasicDBObject("_id", uuid));

    // When
    final List<DBObject> dbObjects = collection.find(new BasicDBObject("_id", uuid)).toArray();

    // Then
    assertThat(dbObjects).containsExactly(new BasicDBObject("_id", uuid));
  }

  // See https://github.com/fakemongo/fongo/issues/166
  @Test
  public void should_not_search_uuid_in_string() {
    // Given
    DBCollection collection = fongoRule.newCollection("db");
    final UUID uuid = UUID.randomUUID();
    collection.insert(new BasicDBObject("_id", uuid));

    // When
    final List<DBObject> dbObjects = collection.find(new BasicDBObject("_id", uuid.toString())).toArray();

    // Then
    assertThat(dbObjects).isEmpty();
  }

  // See https://github.com/fakemongo/fongo/issues/166
  @Test
  public void should_not_search_string_in_uuid() {
    // Given
    DBCollection collection = fongoRule.newCollection("db");
    final UUID uuid = UUID.randomUUID();
    collection.insert(new BasicDBObject("_id", uuid.toString()));

    // When
    final List<DBObject> dbObjects = collection.find(new BasicDBObject("_id", uuid)).toArray();

    // Then
    assertThat(dbObjects).isEmpty();
  }

  @Test
  public void should_not_rename_a_collection_if_not_admin() {
    // Given
    DBCollection collection = fongoRule.getDB("olddb").getCollection("oldName");
    collection.drop();
    fongoRule.getDB("newdb").getCollection("newcollection").drop();
    collection.insert(new BasicDBObject("_id", 1));
    collection.createIndex(new BasicDBObject("date", 1));

    // When
    final CommandResult commandResult = collection.getDB().command(new BasicDBObject("renameCollection", "olddb.oldName").append("to", "newdb.newcollection"));
    DBCollection second = fongoRule.getDB("newdb").getCollection("newcollection");

    // Then
    assertThat(commandResult.getErrorMessage()).isEqualTo("renameCollection may only be run against the admin database.");
    assertThat(commandResult.ok()).isFalse();
    assertThat(second.getFullName()).isEqualTo("newdb.newcollection");
    assertThat(collection.getFullName()).isEqualTo("olddb.oldName");
  }

  @Test
  public void should_fully_rename_a_collection() {
    // Given
    DBCollection collection = fongoRule.getDB("olddb").getCollection("oldName");
    collection.drop();
    fongoRule.getDB("newdb").getCollection("newcollection").drop();
    collection.insert(new BasicDBObject("_id", 1));
    collection.createIndex(new BasicDBObject("date", 1));

    // When
    final CommandResult commandResult = fongoRule.getDB("admin").command(new BasicDBObject("renameCollection", "olddb.oldName").append("to", "newdb.newcollection"));
    DBCollection second = fongoRule.getDB("newdb").getCollection("newcollection");

    // Then
    assertThat(commandResult.getErrorMessage()).isNull();
    assertThat(commandResult.ok()).isTrue();
    assertThat(second.getFullName()).isEqualTo("newdb.newcollection");
    assertThat(collection.getFullName()).isEqualTo("olddb.oldName");
    assertThat(fongoRule.getMongo().getDB("newdb").getCollection("newcollection").find().toArray()).containsExactly(new BasicDBObject("_id", 1));
    assertThat(fongoRule.getMongo().getDB("newdb").getCollection("newcollection").getIndexInfo()).hasSize(2);
  }

  @Test
  public void should_$each_works_with_array() {
    // Given
    DBCollection collection = fongoRule.newCollection("db");
    collection.insert(new BasicDBObject("_id", 1));

    // When
    final String[] value = new String[]{"1", "2", "3", "4"};
    collection.update(new BasicDBObject("_id", 1), new BasicDBObject("$push", new BasicDBObject("value", new BasicDBObject("$each", value))));

    // Then
    assertThat(collection.find().toArray()).containsExactly(new BasicDBObject("_id", 1).append("value", value));
  }

  @Test
  public void should_$each_works_with_long_array() {
    // Given
    DBCollection collection = fongoRule.newCollection("db");
    collection.insert(new BasicDBObject("_id", 1));

    // When
    final long[] value = new long[]{1L, 2L, 3L, 4L};
    collection.update(new BasicDBObject("_id", 1), new BasicDBObject("$push", new BasicDBObject("value", new BasicDBObject("$each", value))));

    // Then
    assertThat(collection.find().toArray()).containsExactly(new BasicDBObject("_id", 1).append("value", value));
  }

  @Test
  public void should_$eq_on_empty_array_works() {
    // Given
    DBCollection collection = fongoRule.newCollection("db");
    collection.insert(new BasicDBObject("_id", 1).append("tags", new BasicDBList()));

    // When
    final List<DBObject> dbObjects = collection.find(new BasicDBObject("tags", new BasicDBObject("$eq", new BasicDBList()))).toArray();

    // Then
    assertThat(dbObjects).containsExactly(new BasicDBObject("_id", 1).append("tags", new BasicDBList()));
  }

  @Test
  public void should_$ne_on_empty_array_works() {
    // Given
    DBCollection collection = fongoRule.newCollection("db");
    collection.insert(new BasicDBObject("_id", 1).append("tags", new BasicDBList()));
    collection.insert(new BasicDBObject("_id", 2).append("tags", Util.list("Hi")));

    // When
    final List<DBObject> dbObjects = collection.find(new BasicDBObject("tags", new BasicDBObject("$ne", new BasicDBList()))).toArray();

    // Then
    assertThat(dbObjects).containsExactly(new BasicDBObject("_id", 2).append("tags", Util.list("Hi")));
  }

  // https://github.com/fakemongo/fongo/issues/201
  @Test
  public void should_$and_have_more_than_2_operands() {
    // Given
    DBCollection collection = fongoRule.newCollection("db");
    collection.insert(new BasicDBObject("_id", 1).append("tags", new BasicDBList()));
    collection.insert(new BasicDBObject("_id", 2).append("tags", Util.list("2")));
    collection.insert(new BasicDBObject("_id", 3).append("tags", Util.list("4")));
    collection.insert(new BasicDBObject("_id", 5).append("ntags", Util.list("4")));

    // When
    final List<DBObject> dbObjects = collection.find(new BasicDBObject("tags", new BasicDBObject("$exists", true).append("$ne", new BasicDBList()).append("$nin", Util.list("1", "2")))).toArray();

    // Then
    assertThat(dbObjects).containsExactly(new BasicDBObject("_id", 3).append("tags", Util.list("4")));
  }

  @Test
  public void should_ping_fongo() {
    // Given
    // When
    final CommandResult ping = fongoRule.getDB().command(new BasicDBObject("ping", 1));

    // Then
    Assertions.assertThat(ping.getErrorMessage()).isNullOrEmpty();
    Assertions.assertThat(ping.ok()).isTrue();
  }

  @Test
  public void should_insert_documents() {
    // Given
    final BasicDBList documentsToAdd = new BasicDBList();

    documentsToAdd.add(new BasicDBObject("a", "document"));
    documentsToAdd.add(new BasicDBObject("b", "document"));
    documentsToAdd.add(new BasicDBObject("c", "document"));

    final DB database = fongoRule.getDB();
    final String aCollection = "aCollection";

    // When
    CommandResult insert = database.command(new BasicDBObject("insert", aCollection).append("documents", documentsToAdd));

    // Then
    assertEquals(new BasicDBObject("ok", 1).append("n", documentsToAdd.size()), insert);
    assertEquals(documentsToAdd.size(), database.getCollection(aCollection).count());
  }

  @Test
  public void should_insert_documents_V3() {
    // Given
    final BasicDBList documentsToAdd = new BasicDBList();

    documentsToAdd.add(new BasicDBObject("a", "document"));
    documentsToAdd.add(new BasicDBObject("b", "document"));
    documentsToAdd.add(new BasicDBObject("c", "document"));

    final MongoDatabase database = fongoRule.getDatabase();
    final String aCollection = "aCollection";

    // When
    Document insert = database.runCommand(new BasicDBObject("insert", aCollection).append("documents", documentsToAdd));

    // Then
    assertEquals(new Document("ok", 1).append("n", documentsToAdd.size()), insert);
    assertEquals(documentsToAdd.size(), database.getCollection(aCollection).count());
  }

  @Test
  public void should_not_delete_documents_if_limit_is_missing() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2));
    collection.insert(new BasicDBObject("_id", 3));

    final BasicDBList deleteQueries = new BasicDBList();

    deleteQueries.add(new BasicDBObject("q", new BasicDBObject("_id", 2)));
    deleteQueries.add(new BasicDBObject("q", new BasicDBObject("_id", 3)));

    final DB database = fongoRule.getDB();

    // When
    CommandResult delete = database.command(new BasicDBObject("delete", collection.getName()).append("deletes", deleteQueries));

    // Then
    assertEquals(new BasicDBObject("ok", 0.0).append("errmsg", "missing limit field").append("code", 9), delete);
  }

  @Test
  public void should_delete_documents_V3() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2));
    collection.insert(new BasicDBObject("_id", 3));

    final BasicDBList deleteQueries = new BasicDBList();

    deleteQueries.add(new BasicDBObject("q", new BasicDBObject("_id", 2)));
    deleteQueries.add(new BasicDBObject("q", new BasicDBObject("_id", 3)));

    final MongoDatabase database = fongoRule.getDatabase();


    ExpectedMongoException.expectMongoCommandException(exception, 9);
    // When
    database.runCommand(new BasicDBObject("delete", collection.getName()).append("deletes", deleteQueries));
  }

  @Test
  public void should_delete_single_document_with_limit1() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2).append("key", "value1"));
    collection.insert(new BasicDBObject("_id", 3).append("key", "value1"));
    collection.insert(new BasicDBObject("_id", 4).append("key", "value1"));
    collection.insert(new BasicDBObject("_id", 5).append("key", "value2"));
    collection.insert(new BasicDBObject("_id", 6).append("key", "value2"));
    collection.insert(new BasicDBObject("_id", 7).append("key", "value2"));

    final BasicDBList deleteQueries = new BasicDBList();

    deleteQueries.add(new BasicDBObject("q", new BasicDBObject("key", "value1")).append("limit", 1));
    deleteQueries.add(new BasicDBObject("q", new BasicDBObject("key", "value2")).append("limit", 1));

    final DB database = fongoRule.getDB();

    // When
    CommandResult delete = database.command(new BasicDBObject("delete", collection.getName()).append("deletes", deleteQueries));

    // Then
    assertEquals(new BasicDBObject("ok", 1).append("n", 2), delete);
    assertEquals(5, database.getCollection(collection.getName()).count());
  }

  @Test
  public void should_delete_single_document_with_limit1_V3() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2).append("key", "value1"));
    collection.insert(new BasicDBObject("_id", 3).append("key", "value1"));
    collection.insert(new BasicDBObject("_id", 4).append("key", "value1"));
    collection.insert(new BasicDBObject("_id", 5).append("key", "value2"));
    collection.insert(new BasicDBObject("_id", 6).append("key", "value2"));
    collection.insert(new BasicDBObject("_id", 7).append("key", "value2"));

    final BasicDBList deleteQueries = new BasicDBList();

    deleteQueries.add(new BasicDBObject("q", new BasicDBObject("key", "value1")).append("limit", 1));
    deleteQueries.add(new BasicDBObject("q", new BasicDBObject("key", "value2")).append("limit", 1));

    final MongoDatabase database = fongoRule.getDatabase();

    // When
    Document delete = database.runCommand(new BasicDBObject("delete", collection.getName()).append("deletes", deleteQueries));

    // Then
    assertEquals(new Document("ok", 1).append("n", 2), delete);
    assertEquals(5, database.getCollection(collection.getName()).count());
  }

  @Test
  public void should_delete_all_documents_with_limit0() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2).append("key", "value1"));
    collection.insert(new BasicDBObject("_id", 3).append("key", "value1"));
    collection.insert(new BasicDBObject("_id", 4).append("key", "value1"));
    collection.insert(new BasicDBObject("_id", 5).append("key", "value2"));
    collection.insert(new BasicDBObject("_id", 6).append("key", "value2"));
    collection.insert(new BasicDBObject("_id", 7).append("key", "value2"));

    final BasicDBList deleteQueries = new BasicDBList();

    deleteQueries.add(new BasicDBObject("q", new BasicDBObject("key", "value1")).append("limit", 0));
    deleteQueries.add(new BasicDBObject("q", new BasicDBObject("key", "value2")).append("limit", 0));

    final DB database = fongoRule.getDB();

    // When
    CommandResult delete = database.command(new BasicDBObject("delete", collection.getName()).append("deletes", deleteQueries));

    // Then
    assertEquals(new BasicDBObject("ok", 1.0).append("n", 6), delete); // 1.0 with fongo, 1 with real mongo.
    assertEquals(1, database.getCollection(collection.getName()).count());
  }

  @Test
  public void should_delete_all_documents_with_limit0_V3() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2).append("key", "value1"));
    collection.insert(new BasicDBObject("_id", 3).append("key", "value1"));
    collection.insert(new BasicDBObject("_id", 4).append("key", "value1"));
    collection.insert(new BasicDBObject("_id", 5).append("key", "value2"));
    collection.insert(new BasicDBObject("_id", 6).append("key", "value2"));
    collection.insert(new BasicDBObject("_id", 7).append("key", "value2"));

    final BasicDBList deleteQueries = new BasicDBList();

    deleteQueries.add(new BasicDBObject("q", new BasicDBObject("key", "value1")).append("limit", 0));
    deleteQueries.add(new BasicDBObject("q", new BasicDBObject("key", "value2")).append("limit", 0));

    final MongoDatabase database = fongoRule.getDatabase();

    // When
    Document delete = database.runCommand(new BasicDBObject("delete", collection.getName()).append("deletes", deleteQueries));

    // Then
    assertEquals(new Document("ok", 1).append("n", 6), delete);
    assertEquals(1, database.getCollection(collection.getName()).count());
  }

  @Test
  public void should_$size_return_empty_arrays() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2).append("array", new BasicDBList()));
    collection.insert(new BasicDBObject("_id", 3).append("array", Util.list("1")));

    // When
    final List<DBObject> dbObjects = collection.find(new BasicDBObject("array", new BasicDBObject("$size", 0))).toArray();

    // Then
    assertThat(dbObjects).hasSize(1).containsOnly(new BasicDBObject("_id", 2).append("array", new BasicDBList()));
  }

  @Test
  // "https://github.com/fakemongo/fongo/issues/225"
  public void should_empty_array_return_empty_arrays() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2).append("array", new BasicDBList()));
    collection.insert(new BasicDBObject("_id", 3).append("array", Util.list("1")));

    // When
    final List<DBObject> dbObjects = collection.find(new BasicDBObject("array", new BasicDBList())).toArray();

    // Then
    assertThat(dbObjects).hasSize(1).containsOnly(new BasicDBObject("_id", 2).append("array", new BasicDBList()));
  }

  @Test
  // "https://github.com/fakemongo/fongo/issues/225"
  public void should_array_return_equals_arrays() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2).append("array", new BasicDBList()));
    collection.insert(new BasicDBObject("_id", 3).append("array", Util.list("1")));
    collection.insert(new BasicDBObject("_id", 4).append("array", Util.list("1", "2")));

    // When
    final List<DBObject> dbObjects = collection.find(new BasicDBObject("array", Util.list("1"))).toArray();

    // Then
    assertThat(dbObjects).hasSize(1).containsOnly(new BasicDBObject("_id", 3).append("array", Util.list("1")));
  }

  @Test
  // "https://github.com/fakemongo/fongo/issues/225"
  public void should_array_return_equals_arrays_only_in_order() {
    // Given
    DBCollection collection = newCollection();
    collection.insert(new BasicDBObject("_id", 1));
    collection.insert(new BasicDBObject("_id", 2).append("array", new BasicDBList()));
    collection.insert(new BasicDBObject("_id", 3).append("array", Util.list("1")));
    collection.insert(new BasicDBObject("_id", 4).append("array", Util.list("2", "1")));
    collection.insert(new BasicDBObject("_id", 5).append("array", Util.list("1", "2")));

    // When
    final List<DBObject> dbObjects = collection.find(new BasicDBObject("array", Util.list("1", "2"))).toArray();

    // Then
    assertThat(dbObjects).hasSize(1).containsOnly(new BasicDBObject("_id", 5).append("array", Util.list("1", "2")));
  }

  @Test
  public void should_getReplicaSetStatus_works() {
    final ReplicaSetStatus replicaSetStatus = fongoRule.getMongo().getReplicaSetStatus();
    Assertions.assertThat(replicaSetStatus).isNull();

  }


  static class Seq {
    Object[] data;

    Seq(Object... data) {
      this.data = data;
    }
  }

  private static <T> Set<T> newHashSet(T... objects) {
    return new HashSet<T>(Arrays.asList(objects));
  }

  public DBCollection newCollection() {
    return fongoRule.newCollection("db");
  }

  private Fongo newFongo() {
    return new Fongo("FongoTest");
  }
}
