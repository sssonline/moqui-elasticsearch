/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.elasticsearch

import groovy.json.JsonOutput
import groovy.transform.CompileStatic
import org.elasticsearch.action.search.SearchRequestBuilder
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.client.Client
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.search.aggregations.AggregationBuilder
import org.elasticsearch.search.aggregations.AggregationBuilders
import org.elasticsearch.search.aggregations.bucket.terms.Terms
import org.elasticsearch.search.aggregations.metrics.sum.Sum
import org.moqui.entity.EntityException
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ExecutionContextImpl
import org.moqui.impl.entity.EntityDataDocument
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.entity.EntityJavaUtil
import org.moqui.impl.entity.FieldInfo
import org.moqui.util.CollectionUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory

@CompileStatic
class ElasticSearchUtil {
    protected final static Logger logger = LoggerFactory.getLogger(ElasticSearchUtil.class)

    static String ddIdToEsIndex(String dataDocumentId) {
        if (dataDocumentId.contains("_")) return dataDocumentId.toLowerCase()
        return EntityJavaUtil.camelCaseToUnderscored(dataDocumentId).toLowerCase()
    }

    // NOTE: called in service scripts
    static boolean checkIndexExists(String indexName, ExecutionContextImpl eci) {
        Client client = (Client) eci.getTool("ElasticSearch", Client.class)
        if (client.admin().indices().prepareAliasesExist(indexName).get().exists) return true
        return client.admin().indices().prepareExists(indexName).get().exists
    }

    // NOTE: called in service scripts
    static synchronized void checkCreateIndex(String indexName, ExecutionContextImpl eci) {
        Client client = (Client) eci.getTool("ElasticSearch", Client.class)

        // if the index alias exists call it good
        if (client.admin().indices().prepareAliasesExist(indexName).get().exists) return

        EntityList ddList = eci.entityFacade.find("moqui.entity.document.DataDocument").condition("indexName", indexName).list()
        for (EntityValue dd in ddList) storeIndexAndMapping(indexName, dd, client, eci)
    }

    // NOTE: called in service scripts
    static synchronized void checkCreateDocIndex(String dataDocumentId, ExecutionContextImpl eci) {
        Client client = (Client) eci.getTool("ElasticSearch", Client.class)

        String idxName = ddIdToEsIndex(dataDocumentId)
        if (client.admin().indices().prepareExists(idxName).get().exists) return

        EntityValue dd = eci.entityFacade.find("moqui.entity.document.DataDocument").condition("dataDocumentId", dataDocumentId).one()
        storeIndexAndMapping((String) dd.indexName, dd, client, eci)
    }

    protected static void storeIndexAndMapping(String indexName, EntityValue dd, Client client, ExecutionContextImpl eci) {
        String dataDocumentId = (String) dd.getNoCheckSimple("dataDocumentId")
        String esIndexName = ddIdToEsIndex(dataDocumentId)
        boolean hasIndex = client.admin().indices().prepareExists(esIndexName).get().exists
        // logger.warn("========== Checking index ${esIndexName} with alias ${indexName} , hasIndex=${hasIndex}")
        Map docMapping = makeElasticSearchMapping(dataDocumentId, eci)
        if (hasIndex) {
            logger.info("Updating ElasticSearch index ${esIndexName} for ${dataDocumentId} with alias ${indexName} document mapping")
            client.admin().indices().preparePutMapping(esIndexName).setType(dataDocumentId).setSource(docMapping).get()
        } else {
            logger.info("Creating ElasticSearch index ${esIndexName} for ${dataDocumentId} with alias ${indexName} and adding document mapping")
            client.admin().indices().prepareCreate(esIndexName).addMapping(dataDocumentId, docMapping).get()
            // logger.warn("========== Added mapping for ${dataDocumentId} to index ${esIndexName}:\n${docMapping}")
            // add an alias (indexName) for the index (dataDocumentId.toLowerCase())
            client.admin().indices().prepareAliases().addAlias(esIndexName, indexName).get()
        }
    }

    // NOTE: called in service scripts
    static void putIndexMappings(String indexName, ExecutionContextImpl eci) {
        Client client = (Client) eci.getTool("ElasticSearch", Client.class)

        EntityList ddList = eci.entity.find("moqui.entity.document.DataDocument").condition("indexName", indexName).list()
        for (EntityValue dd in ddList) storeIndexAndMapping(indexName, dd, client, eci)
    }

    static final Map<String, String> esTypeMap = [id:'keyword', 'id-long':'keyword', date:'date', time:'text',
            'date-time':'date', 'number-integer':'long', 'number-decimal':'double', 'number-float':'double',
            'currency-amount':'double', 'currency-precise':'double', 'text-indicator':'keyword', 'text-short':'text',
            'text-medium':'text', 'text-long':'text', 'text-very-long':'text', 'binary-very-long':'binary']

    static Map makeElasticSearchMapping(String dataDocumentId, ExecutionContextImpl eci) {
        EntityValue dataDocument = eci.entityFacade.find("moqui.entity.document.DataDocument")
                .condition("dataDocumentId", dataDocumentId).useCache(true).one()
        if (dataDocument == null) throw new EntityException("No DataDocument found with ID [${dataDocumentId}]")
        EntityList dataDocumentFieldList = dataDocument.findRelated("moqui.entity.document.DataDocumentField", null, null, true, false)
        EntityList dataDocumentRelAliasList = dataDocument.findRelated("moqui.entity.document.DataDocumentRelAlias", null, null, true, false)

        Map<String, String> relationshipAliasMap = [:]
        for (EntityValue dataDocumentRelAlias in dataDocumentRelAliasList)
            relationshipAliasMap.put((String) dataDocumentRelAlias.relationshipName, (String) dataDocumentRelAlias.documentAlias)

        String primaryEntityName = dataDocument.primaryEntityName
        // String primaryEntityAlias = relationshipAliasMap.get(primaryEntityName) ?: primaryEntityName
        EntityDefinition primaryEd = eci.entityFacade.getEntityDefinition(primaryEntityName)

        Map<String, Object> rootProperties = [_entity:[type:'keyword']] as Map<String, Object>
        Map<String, Object> mappingMap = [properties:rootProperties] as Map<String, Object>

        List<String> remainingPkFields = new ArrayList(primaryEd.getPkFieldNames())
        for (EntityValue dataDocumentField in dataDocumentFieldList) {
            String fieldPath = (String) dataDocumentField.fieldPath
            ArrayList<String> fieldPathElementList = EntityDataDocument.fieldPathToList(fieldPath)
            if (fieldPathElementList.size() == 1) {
                // is a field on the primary entity, put it there
                String fieldName = ((String) dataDocumentField.fieldNameAlias) ?: fieldPath
                String mappingType = (String) dataDocumentField.fieldType
                String sortable = (String) dataDocumentField.sortable
                if (fieldPath.startsWith("(")) {
                    rootProperties.put(fieldName, makePropertyMap(null, mappingType ?: 'double', sortable))
                } else {
                    FieldInfo fieldInfo = primaryEd.getFieldInfo(fieldPath)
                    if (fieldInfo == null) throw new EntityException("Could not find field [${fieldPath}] for entity [${primaryEd.getFullEntityName()}] in DataDocument [${dataDocumentId}]")
                    rootProperties.put(fieldName, makePropertyMap(fieldInfo.type, mappingType, sortable))
                    if (remainingPkFields.contains(fieldPath)) remainingPkFields.remove(fieldPath)
                }

                continue
            }

            Map<String, Object> currentProperties = rootProperties
            EntityDefinition currentEd = primaryEd
            int fieldPathElementListSize = fieldPathElementList.size()
            for (int i = 0; i < fieldPathElementListSize; i++) {
                String fieldPathElement = (String) fieldPathElementList.get(i)
                if (i < (fieldPathElementListSize - 1)) {
                    EntityJavaUtil.RelationshipInfo relInfo = currentEd.getRelationshipInfo(fieldPathElement)
                    if (relInfo == null) throw new EntityException("Could not find relationship [${fieldPathElement}] for entity [${currentEd.getFullEntityName()}] in DataDocument [${dataDocumentId}]")
                    currentEd = relInfo.relatedEd
                    if (currentEd == null) throw new EntityException("Could not find entity [${relInfo.relatedEntityName}] in DataDocument [${dataDocumentId}]")

                    // only put type many in sub-objects, same as DataDocument generation
                    if (!relInfo.isTypeOne) {
                        String objectName = relationshipAliasMap.get(fieldPathElement) ?: fieldPathElement
                        Map<String, Object> subObject = (Map<String, Object>) currentProperties.get(objectName)
                        Map<String, Object> subProperties
                        if (subObject == null) {
                            subProperties = new HashMap<>()
                            // using type:'nested' with include_in_root:true seems to support nested queries and currently works with query string full path field names too
                            // NOTE: keep an eye on this and if it breaks for our primary use case which is query strings with full path field names then remove type:'nested' and include_in_root
                            subObject = [properties:subProperties, type:'nested', include_in_root:true] as Map<String, Object>
                            currentProperties.put(objectName, subObject)
                        } else {
                            subProperties = (Map<String, Object>) subObject.get("properties")
                        }
                        currentProperties = subProperties
                    }
                } else {
                    String fieldName = (String) dataDocumentField.fieldNameAlias ?: fieldPathElement
                    String mappingType = (String) dataDocumentField.fieldType
                    String sortable = (String) dataDocumentField.sortable
                    if (fieldPathElement.startsWith("(")) {
                        currentProperties.put(fieldName, makePropertyMap(null, mappingType ?: 'double', sortable))
                    } else {
                        FieldInfo fieldInfo = currentEd.getFieldInfo(fieldPathElement)
                        if (fieldInfo == null) throw new EntityException("Could not find field [${fieldPathElement}] for entity [${currentEd.getFullEntityName()}] in DataDocument [${dataDocumentId}]")
                        currentProperties.put(fieldName, makePropertyMap(fieldInfo.type, mappingType, sortable))
                    }
                }
            }
        }

        // now get all the PK fields not aliased explicitly
        for (String remainingPkName in remainingPkFields) {
            FieldInfo fieldInfo = primaryEd.getFieldInfo(remainingPkName)
            String mappingType = esTypeMap.get(fieldInfo.type) ?: 'keyword'
            Map propertyMap = makePropertyMap(null, mappingType, null)
            // don't use not_analyzed in more recent ES: if (fieldInfo.type.startsWith("id")) propertyMap.index = 'not_analyzed'
            rootProperties.put(remainingPkName, propertyMap)
        }

        if (logger.isTraceEnabled()) logger.trace("Generated ElasticSearch mapping for ${dataDocumentId}: \n${JsonOutput.prettyPrint(JsonOutput.toJson(mappingMap))}")

        return mappingMap
    }
    static Map makePropertyMap(String fieldType, String mappingType, String sortable) {
        if (!mappingType) mappingType = esTypeMap.get(fieldType) ?: 'text'
        Map<String, Object> propertyMap = new LinkedHashMap<>()
        propertyMap.put("type", mappingType)
        if ("Y".equals(sortable) && "text".equals(mappingType)) propertyMap.put("fields", [keyword: [type: "keyword"]])
        if ("date".equals(mappingType)) propertyMap.format = "strict_date_optional_time||epoch_millis||yyyy-MM-dd HH:mm:ss.SSS||yyyy-MM-dd HH:mm:ss.S||yyyy-MM-dd"
        // if (fieldType.startsWith("id")) propertyMap.index = 'not_analyzed'
        return propertyMap
    }

    static SearchResponse aggregationSearch(String indexName, List<String> documentTypeList, Integer maxResults, Map queryMap,
                                            AggregationBuilder aggBuilder, ExecutionContextImpl eci) {
        String queryJson = queryMap != null ? JsonOutput.toJson(queryMap) : null
        // logger.warn("aggregationSearch queryJson: ${JsonOutput.prettyPrint(queryJson)}")

        Client elasticSearchClient = (Client) eci.getTool("ElasticSearch", Client.class)
        // make sure index exists
        checkCreateIndex(indexName, eci)

        // get the search hits
        SearchRequestBuilder srb = elasticSearchClient.prepareSearch().setIndices(indexName)
        if (maxResults != null) srb.setSize(maxResults)
        if (documentTypeList) srb.setTypes((String[]) documentTypeList.toArray(new String[documentTypeList.size()]))
        if (queryJson) srb.setQuery(QueryBuilders.wrapperQuery(queryJson))
        srb.addAggregation(aggBuilder)
        // logger.warn("aggregationSearch srb: ${srb.toString()}")

        try {
            SearchResponse searchResponse = srb.execute().actionGet()
            // aggregations = searchResponse.getAggregations().getAsMap()
            // responseString = searchResponse.toString()
            return searchResponse
        } catch (Exception e) {
            logger.error("Error in search: ${e.toString()}\nQuery JSON:\n${queryJson ? JsonOutput.prettyPrint(queryJson) : ''}")
            throw e
        }
    }

    static void simpleAggSearch(String indexName, List<String> documentTypeList, Integer maxResults,
                                Map queryMap, String termAggField, Map<String, String> sumAggFieldByName,
                                Map<String, Map<String, Object>> resultMap, ExecutionContextImpl eci) {
        if (maxResults == null) maxResults = 1000
        AggregationBuilder termAggBuilder = AggregationBuilders.terms("TermSimple").field(termAggField).size(maxResults)

        int sumAggSize = sumAggFieldByName.size()
        ArrayList<String> sumAggNames = new ArrayList<>(sumAggSize)
        for (Map.Entry<String, String> entry in sumAggFieldByName.entrySet()) {
            String sumAggName = entry.key
            String field = entry.value
            termAggBuilder.subAggregation(AggregationBuilders.sum(sumAggName).field(field))
            sumAggNames.add(sumAggName)
        }

        SearchResponse searchResponse = aggregationSearch(indexName, documentTypeList, maxResults, queryMap, termAggBuilder, eci)

        Terms termSimpleAgg = (Terms) searchResponse.getAggregations().get("TermSimple")
        for (Terms.Bucket bucket in termSimpleAgg.getBuckets()) {
            for (int i = 0; i < sumAggSize; i++) {
                String sumAggName = (String) sumAggNames.get(i)
                Sum sumAgg = (Sum) bucket.getAggregations().get(sumAggName)
                CollectionUtilities.addToMapInMap(bucket.getKey(), sumAggName, new BigDecimal(sumAgg.getValue()), resultMap)
            }
        }
    }
}
