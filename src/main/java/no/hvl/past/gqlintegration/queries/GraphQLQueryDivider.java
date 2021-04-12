package no.hvl.past.gqlintegration.queries;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.hvl.past.gqlintegration.GraphQLEndpoint;
import no.hvl.past.gqlintegration.caller.IntrospectionQuery;
import no.hvl.past.gqlintegration.predicates.MutationMessage;
import no.hvl.past.gqlintegration.predicates.QueryMesage;
import no.hvl.past.gqlintegration.schema.FieldMult;
import no.hvl.past.gqlintegration.schema.GraphQLSchemaWriter;
import no.hvl.past.gqlintegration.schema.StubWiring;
import no.hvl.past.graph.GraphMorphism;
import no.hvl.past.graph.Sketch;
import no.hvl.past.graph.elements.Triple;
import no.hvl.past.graph.predicates.*;
import no.hvl.past.graph.trees.*;
import no.hvl.past.keys.Key;
import no.hvl.past.keys.KeyNotEvaluated;
import no.hvl.past.names.Name;
import no.hvl.past.names.PrintingStrategy;
import no.hvl.past.systems.ComprSys;
import no.hvl.past.systems.MessageArgument;
import no.hvl.past.systems.Sys;
import no.hvl.past.util.IOStreamUtils;
import no.hvl.past.util.Pair;
import org.apache.log4j.Logger;

import java.io.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GraphQLQueryDivider extends GraphQLQueryHandler {

    private Logger logger = Logger.getLogger(GraphQLQueryDivider.class);

    // TODO make cursor its own class to have two advantages: 1) it can pre-calculate local names and field modifiers while requests are processed aynchronously 2) can make it technology independent of JSON

    private enum TraversePosition {
        MERGED_OBJECT,
        MERGED_FIELD,
        MERGED_OBJECT_FUSE_ARRAY,
        MERGED_OBJECT_CONCAT_ARRAY,
        MERGED_VALUE_ARRAY,
        LOCAL_OBJECT,
        LOCAL_FIELD,
        OBJECT_ARRAY,
        VALUE_ARRAY,
        VALUE
    }

    private class TraverseStep {
        private final Map<Sys, JsonNode> cursorPositions;
        private final TraversePosition position;
        private final JsonGenerator target;
        private final QueryNodeChildren currentEdge;

        private TraverseStep(Map<Sys, JsonNode> cursorPositions,
                            TraversePosition position,
                            JsonGenerator target,
                             QueryNodeChildren currentEdge) {
            this.cursorPositions = cursorPositions;
            this.position = position;
            this.target = target;
            this.currentEdge = currentEdge;
        }

        public void advance() throws IOException, KeyNotEvaluated {
            switch (position) {
                case VALUE:
                    handleValue();
                    break;
                case LOCAL_FIELD:
                    handleField();
                    break;
                case VALUE_ARRAY:
                    handleArray(false);
                    break;
                case OBJECT_ARRAY:
                    handleArray(true);
                    break;
                case LOCAL_OBJECT:
                    handleObject();
                    break;
                case MERGED_OBJECT:
                    handleMergedObject();
                    break;
                case MERGED_VALUE_ARRAY:
                    handleConcat(false);
                    break;
                case MERGED_OBJECT_CONCAT_ARRAY:
                    handleConcat(true);
                    break;
                case MERGED_OBJECT_FUSE_ARRAY:
                    handleMergeObjects();
                    break;
                case MERGED_FIELD:
                    handleMergedField();
                    break;
                default:
                    break;
            }
        }

        private void handleMergeObjects() throws KeyNotEvaluated, IOException {
            // TODO working with multiple keys must be addressed
            List<Key> keys = comprSys.keys().filter(k -> k.targetType().equals(currentEdge.feature().getTarget())).collect(Collectors.toList());
            Set<Name> toIterate = new LinkedHashSet<>();
            for (Sys ep : cursorPositions.keySet()) {
                JsonNode node = cursorPositions.get(ep);
                if (node.isArray()) {
                    Iterator<JsonNode> iterator = node.iterator();
                    while (iterator.hasNext()) {
                        JsonNode n = iterator.next();
                        for (Key key : keys) {
                            Name k = key.evaluate(n);
                            toIterate.add(k);
                            addToCache(ep, k, n);
                        }
                    }
                } else {
                    for (Key key : keys) {
                        Name k = key.evaluate(node);
                        toIterate.add(k);
                        addToCache(ep, k , node);
                    }
                }
            }
            for (Name k : toIterate) {
                Map<Sys, JsonNode> mergedCursor = objectCursorCache.get(k);
                if (mergedCursor.keySet().size() <= 1) {
                    new TraverseStep(mergedCursor, TraversePosition.LOCAL_OBJECT, target, currentEdge).advance();
                } else {
                    new TraverseStep(mergedCursor, TraversePosition.MERGED_OBJECT, target, currentEdge).advance();
                }
            }
        }

        private void addToCache(Sys endpoint, Name key, JsonNode node) {
            if (objectCursorCache.containsKey(key)) {
                objectCursorCache.get(key).put(endpoint, node);
            } else {
                Map<Sys, JsonNode> cursorPos = new LinkedHashMap<>();
                cursorPos.put(endpoint, node);
                objectCursorCache.put(key, cursorPos);
            }
        }

        private void handleMergedField() throws IOException, KeyNotEvaluated {
            boolean hasKeys = comprSys.keys().anyMatch(k -> k.targetType().equals(currentEdge.feature().getTarget()));
            boolean isObject = hasObjectReturnType(currentEdge.feature());
            target.writeFieldName(currentEdge.key().print(PrintingStrategy.IGNORE_PREFIX));
            if (isObject) {
                if (hasKeys) {
                    new TraverseStep(cursorPositions, TraversePosition.MERGED_OBJECT_FUSE_ARRAY, target, currentEdge).advance();
                } else {
                    new TraverseStep(cursorPositions, TraversePosition.MERGED_OBJECT_CONCAT_ARRAY, target, currentEdge).advance();
                }
            } else {
                new TraverseStep(cursorPositions, TraversePosition.MERGED_VALUE_ARRAY, target, currentEdge).advance();
            }
        }

        private void handleConcat(boolean isObject) throws IOException, KeyNotEvaluated {
            target.writeStartArray();
            for (Sys ep : cursorPositions.keySet()) {
                TraversePosition nextPos;
                if (isObject) {
                        nextPos = TraversePosition.LOCAL_OBJECT;
                } else {
                    nextPos = TraversePosition.VALUE;
                }
                JsonNode root = cursorPositions.get(ep);
                if (root.isArray()) {
                        Iterator<JsonNode> iterator = root.iterator();
                        while (iterator.hasNext()) {
                            JsonNode node = iterator.next();
                                new TraverseStep(
                                        Collections.singletonMap(ep, node),
                                        nextPos,
                                        target,
                                        currentEdge).advance();

                        }
                    } else {
                            new TraverseStep(
                                    Collections.singletonMap(ep, root),
                                    nextPos,
                                    target,
                                    currentEdge).advance();
                }
            }
            target.writeEndArray();
        }



        private void handleMergedObject() throws IOException, KeyNotEvaluated {
            target.writeStartObject();
            for (QueryNodeChildren child : currentEdge.child().children().collect(Collectors.toList())) {
                Map<Sys, JsonNode> canDeliver = new LinkedHashMap<>();
                for (Sys ep : cursorPositions.keySet()) {
                    if (comprSys.localNames(ep, child.feature().getLabel()).anyMatch(x -> true)) {
                        canDeliver.put(ep, cursorPositions.get(ep));
                    }
                }
                if (canDeliver.size() > 1) {
                    new TraverseStep(canDeliver,
                            TraversePosition.MERGED_FIELD,
                            target,
                            child).advance();
                } else if (canDeliver.size() == 1) {
                    new TraverseStep(canDeliver,
                            TraversePosition.LOCAL_FIELD,
                            target,
                            child).advance();
                } else {
                    target.writeFieldName(child.key().print(PrintingStrategy.IGNORE_PREFIX));
                    target.writeNull();
                }
            }
            target.writeEndObject();
        }


        private void handleObject() throws IOException, KeyNotEvaluated {
            target.writeStartObject();
            Sys key = cursorPositions.keySet().iterator().next();
            JsonNode jsonNode = cursorPositions.get(key);
            for (QueryNodeChildren c : currentEdge.child().children().collect(Collectors.toList())) {
                new TraverseStep(Collections.singletonMap(key, jsonNode),
                        TraversePosition.LOCAL_FIELD,
                        target,
                        c).advance();
            }
            target.writeEndObject();
        }

        private void handleArray(boolean isObject) throws IOException, KeyNotEvaluated {
            target.writeStartArray();
            Sys key = cursorPositions.keySet().iterator().next();
            JsonNode jsonNode = cursorPositions.get(key);
            Iterator<JsonNode> iterator = jsonNode.iterator();
            while (iterator.hasNext()) {
                new TraverseStep(Collections.singletonMap(key, iterator.next()),
                        isObject ?  TraversePosition.LOCAL_OBJECT : TraversePosition.VALUE,
                        target,
                        currentEdge).advance();
            }
            target.writeEndArray();
        }

        private void handleField() throws IOException, KeyNotEvaluated {
            Sys key = cursorPositions.keySet().iterator().next();
            Set<Name> localNames = comprSys.localNames(key, currentEdge.feature().getLabel()).collect(Collectors.toSet());
            if (!localNames.isEmpty()) {
                target.writeFieldName(currentEdge.key().print(PrintingStrategy.IGNORE_PREFIX));
                boolean listValued = isListValued(currentEdge.feature());
                boolean isObject = hasObjectReturnType(currentEdge.feature());
                if (listValued || localNames.size() > 1) {
                    for (Name localName : localNames) {
                        String fName = key.displayName(localName);
                        if (isObject) {
                            new TraverseStep(Collections.singletonMap(key, cursorPositions.get(key).get(fName)), TraversePosition.OBJECT_ARRAY, target, currentEdge).advance();
                        } else {
                            new TraverseStep(Collections.singletonMap(key, cursorPositions.get(key).get(fName)), TraversePosition.VALUE_ARRAY, target, currentEdge).advance();
                        }
                    }
                } else {
                    String fName = key.displayName(localNames.iterator().next());
                    if (isObject) {
                        new TraverseStep(Collections.singletonMap(key, cursorPositions.get(key).get(fName)), TraversePosition.LOCAL_OBJECT, target, currentEdge).advance();
                    } else {
                        new TraverseStep(Collections.singletonMap(key, cursorPositions.get(key).get(fName)), TraversePosition.VALUE, target, currentEdge).advance();
                    }
                }

            }
        }

        private void handleValue() throws IOException {
            Sys ep = cursorPositions.keySet().iterator().next();
            JsonNode jsonNode = cursorPositions.get(ep);
            if (jsonNode.isTextual()) {
                target.writeString(jsonNode.asText());
            } else if (jsonNode.isIntegralNumber()) {
                target.writeNumber(jsonNode.asLong());
            } else if (jsonNode.isFloatingPointNumber()) {
                target.writeNumber(jsonNode.asDouble());
            } else if (jsonNode.isBoolean()) {
                target.writeBoolean(jsonNode.asBoolean());
            } else if (jsonNode.isNull()) {
                target.writeNull();
            } else {
                target.writeRaw(jsonNode.toString());
            }
        }

    }

    private Set<String> localNames(Sys ep, Name fieldName) {
        return comprSys.localNames(ep, fieldName).map(ep::displayName).collect(Collectors.toSet());
    }

    private boolean isListValued(Triple edge) {
        // TODO replace with a respective method in system
        return comprSys.schema().diagramsOn(edge).noneMatch(diag -> TargetMultiplicity.class.isAssignableFrom(diag.label().getClass()) && ((TargetMultiplicity) diag.label()).getUpperBound() <= 1);
    }

    private boolean hasObjectReturnType(Triple edge) {
        return !comprSys.isAttributeType(edge);
    }

    // TODO remove
    private final Map<Name, Map<Sys, JsonNode>> objectCursorCache;

    private ComprSys comprSys;
    private Map<Sys, QueryHandler> localHandlers;
    private GraphQL javaGraphQLEngine;

    public GraphQLQueryDivider(ComprSys comprSys, Map<Sys, QueryHandler> localHandlers, GraphQL javaGraphQLEngine, GraphQLEndpoint endpoint) {
        super(endpoint);
        this.comprSys = comprSys;
        this.localHandlers = localHandlers;
        this.javaGraphQLEngine = javaGraphQLEngine;
        // TODO remove
        this.objectCursorCache = new LinkedHashMap<>();
    }


    @Override
    public void handle(InputStream i, OutputStream o) throws IOException {
        try {
            LocalDateTime parseStart = LocalDateTime.now();
            TypedTree typedTree = deserialize(i);
            LocalDateTime parseEnd = LocalDateTime.now();
            System.out.println("Query parsing: " + Duration.between(parseStart, parseEnd).toMillis() + " ms");

            if (typedTree instanceof IntrospectionQuery) {
                this.handleIntrospectionQuery((IntrospectionQuery) typedTree, o);
            } else if (typedTree instanceof GraphQLQuery) {
                GraphQLQuery globalQuery = (GraphQLQuery) typedTree;
                Map<Sys, GraphQLQuery> localQueries = split(globalQuery);
                Map<Sys, InputStream> localQueryResults = executeQueries(localQueries);
                merge(localQueryResults, globalQuery, o);
            } else {
                throw new IOException("Cannot handle this query!");
            }
        } catch (KeyNotEvaluated keyNotEvaluated) {
            throw new IOException(keyNotEvaluated);
        }
    }

    public void handleIntrospectionQuery(IntrospectionQuery query, OutputStream os) throws IOException {
        if (query.getOperationName().isPresent()) {
            ExecutionInput e = new ExecutionInput.Builder()
                    .query(query.getQuery())
                    .operationName(query.getOperationName().get())
                    .variables(query.getVariables().get())
                    .build();
            ExecutionResult execute = javaGraphQLEngine.execute(e);
            Map<String, Object> spec = execute.toSpecification();
            getObjectMapper().writeValue(os, spec);
        } else {
            ExecutionResult executionResult = javaGraphQLEngine.execute(query.getQuery());
            Map<String, Object> spec = executionResult.toSpecification();
            getObjectMapper().writeValue(os, spec);
        }
    }




    public void merge(
            Map<Sys, InputStream> localQueryResults,
            GraphQLQuery originalQuery,
            OutputStream outputStream) throws IOException, KeyNotEvaluated {
        IOStreamUtils.Wiretap wiretap = new IOStreamUtils.Wiretap(outputStream);
        JsonGenerator generator = getJsonFactory().createGenerator(wiretap);
        generator.writeStartObject();
        generator.writeFieldName("data");
        generator.writeStartObject();

        LocalDateTime localQRepsParse = LocalDateTime.now();
        Map<Sys, JsonNode> globalResults = new LinkedHashMap<>();
        for (Sys ep : localQueryResults.keySet()) {
            JsonNode jsonNode = getObjectMapper().readTree(localQueryResults.get(ep)).get("data");
            globalResults.put(ep, jsonNode);
        }
        LocalDateTime localQRepsParseStop = LocalDateTime.now();
        System.out.println("Parsing Response from local Query: " + Duration.between(localQRepsParse, localQRepsParseStop).toMillis() + " ms");

        LocalDateTime startMerge = LocalDateTime.now();
        for (GraphQLQuery.QueryRoot queryRoot : originalQuery.getRoots()) {
            Map<String, JsonNode> paramMap = new LinkedHashMap<>();
            for (Sys endpoint : globalResults.keySet()) {
                paramMap.put(endpoint.url(), globalResults.get(endpoint));
            }
            QueryCursor.ConcatCursor cursor = (QueryCursor.ConcatCursor) queryRoot.getCursor().get();
            cursor.addResults(paramMap);
            cursor.processOne(generator);
        }
        LocalDateTime finishMerge = LocalDateTime.now();
        System.out.println("Merging Query Response: " + Duration.between(startMerge, finishMerge).toMillis() + " ms");


//        for (QueryNode.Root root : originalQuery.queryRoots().collect(Collectors.toList())) {
//            boolean hasResult = false;
//
//            Map<Sys, JsonNode> cursors = new LinkedHashMap<>();
//            for (Sys ep : localQueryResults.keySet()) {
//                LocalDateTime localQRepsParse = LocalDateTime.now();
//                JsonNode jsonNode = getObjectMapper().readTree(localQueryResults.get(ep)).get("data");
//                LocalDateTime localQRepsParseStop = LocalDateTime.now();
//                System.out.println("Parsing Response from local Query: " + Duration.between(localQRepsParse, localQRepsParseStop).toMillis() + " ms");
//
//                for (Name localName : comprSys.localNames(ep, root.messageType()).collect(Collectors.toList())) {
//                    String field = ep.displayName(localName);
//                    if (jsonNode.get(field) != null) {
//                        cursors.put(ep, jsonNode.get(field));
//                        hasResult = true;
//                    }
//                }
//            }
//            if (hasResult) {
//                generator.writeFieldName(root.branchName().print(PrintingStrategy.IGNORE_PREFIX));
//                LocalDateTime mergeStart = LocalDateTime.now();
//
//                new TraverseStep(cursors,
//                        TraversePosition.MERGED_OBJECT_CONCAT_ARRAY, // TODO check for keys
//                        generator,
//                        root.asEdge()).advance();
//                LocalDateTime meregStop = LocalDateTime.now();
//                System.out.println("Merging results: " + Duration.between(mergeStart, meregStop).toMillis() + " ms");
//
//
//            } else {
//                generator.writeFieldName(root.branchName().print(PrintingStrategy.IGNORE_PREFIX));
//                generator.writeNull();
//            }
//        }
        generator.writeEndObject();
        generator.writeEndObject();
        generator.flush();
        System.out.println(wiretap.getRecorded());

        outputStream.close();
    }


    private Map<Sys, InputStream> executeQueries(Map<Sys, GraphQLQuery> localQueries)  throws IOException {
        LocalDateTime qSendStart = LocalDateTime.now();
        Map<Sys, InputStream> localQueryResults = new LinkedHashMap<>();
        for (Sys ep : localQueries.keySet()) {
            if (localHandlers.containsKey(ep)) {
                localQueryResults.put(ep, localHandlers.get(ep).resolveAsStream(localQueries.get(ep)));
            }
        }
        LocalDateTime qSendEnd = LocalDateTime.now();
        System.out.println("Local Query Request/Response: " + Duration.between(qSendStart, qSendEnd).toMillis() + " ms");

        return localQueryResults;
    }


    public Map<Sys, GraphQLQuery> split(GraphQLQuery query) {
        LocalDateTime splitStart = LocalDateTime.now();
        Map<Sys, GraphQLQuery> result =  query.split(comprSys, new ArrayList<>(this.localHandlers.keySet()));
        LocalDateTime splitEnd = LocalDateTime.now();
        System.out.println("Query Splitting: " + Duration.between(splitStart, splitEnd).toMillis() + " ms");
        return result;
    }




    public static GraphQLQueryHandler create(
            ObjectMapper objectMapper,
            JsonFactory factory,
            ComprSys comprSys,
            LinkedHashMap<Sys, QueryHandler> handlerMap) throws IOException {
        // writing schema
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        GraphQLSchemaWriter schemaWriter = new GraphQLSchemaWriter();
        comprSys.schema().accept(schemaWriter);
        BufferedWriter bufferedWriter = new BufferedWriter(new OutputStreamWriter(bos));
        schemaWriter.printToBuffer(bufferedWriter);
        bufferedWriter.flush();
        bufferedWriter.close();

        // building GraphQL engine
        TypeDefinitionRegistry typeReg = new SchemaParser().parse(bos.toString("UTF-8"));
        RuntimeWiring wiring = StubWiring.createWiring(typeReg);
        GraphQLSchema executableSchema = new SchemaGenerator().makeExecutableSchema(typeReg, wiring);
        GraphQL graphQL = GraphQL.newGraphQL(executableSchema).build();

        GraphQLEndpoint endpoint = new GraphQLEndpoint(
                comprSys.url(),
                comprSys.schema(),
                schemaWriter.getNameToText(),
                schemaWriter.getMults(),
                comprSys.messages().filter(m -> m instanceof QueryMesage).map(m -> (QueryMesage) m).collect(Collectors.toSet()),
                comprSys.messages().filter(m -> m instanceof MutationMessage).map(m -> (MutationMessage) m).collect(Collectors.toSet()),
                objectMapper,
                factory,
                "Query",
                "Mutation");
        return new GraphQLQueryDivider(comprSys, handlerMap, graphQL, endpoint);
    }
}
