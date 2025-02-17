/*
 * Copyright 2017-2020 EPAM Systems, Inc. (https://www.epam.com/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.epam.pipeline.manager.log;

import com.epam.pipeline.common.MessageConstants;
import com.epam.pipeline.common.MessageHelper;
import com.epam.pipeline.entity.log.LogEntry;
import com.epam.pipeline.entity.log.LogFilter;
import com.epam.pipeline.entity.log.LogPagination;
import com.epam.pipeline.entity.log.LogPaginationRequest;
import com.epam.pipeline.entity.log.PageMarker;
import com.epam.pipeline.entity.utils.DateUtils;
import com.epam.pipeline.exception.PipelineException;
import com.epam.pipeline.manager.utils.GlobalSearchElasticHelper;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.MultiBucketsAggregation;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@Getter
@Setter
@RequiredArgsConstructor
public class LogManager {

    private static final String ES_WILDCARD = "*";
    private static final DateTimeFormatter ELASTIC_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final IndicesOptions INDICES_OPTIONS = IndicesOptions.fromOptions(true,
            SearchRequest.DEFAULT_INDICES_OPTIONS.allowNoIndices(),
            SearchRequest.DEFAULT_INDICES_OPTIONS.expandWildcardsOpen(),
            SearchRequest.DEFAULT_INDICES_OPTIONS.expandWildcardsClosed(),
            SearchRequest.DEFAULT_INDICES_OPTIONS);

    private static final String USER = "user";
    private static final String TIMESTAMP = "@timestamp";
    private static final String MESSAGE_TIMESTAMP = "message_timestamp";
    private static final String HOSTNAME = "hostname";
    private static final String SERVICE_NAME = "service_name";
    private static final String TYPE = "type";
    private static final String MESSAGE = "message";
    private static final String SEVERITY = "level";
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    private static final String DEFAULT_SEVERITY = "INFO";
    private static final String ID = "event_id";
    private static final String SERVICE_ACCOUNT = "service_account";
    private static final String KEYWORD = ".keyword";
    private static final Period FILEBEAT_TRANSITION_PERIOD = Period.ofDays(1);

    private final GlobalSearchElasticHelper elasticHelper;
    private final MessageHelper messageHelper;

    @Value("${log.security.elastic.index.prefix:security_log}")
    private String indexPrefix;

    /**
     * Searches log according to specified log filter.
     * @param logFilter - filter for constructing elasticsearch query
     * @return {@link LogPagination} object with related search result and additional information
     * */
    public LogPagination filter(final LogFilter logFilter) {
        final LogPaginationRequest pagination = logFilter.getPagination();

        Assert.notNull(pagination, messageHelper.getMessage(MessageConstants.ERROR_PAGINATION_IS_NOT_PROVIDED));
        Assert.isTrue(pagination.getPageSize() > 0,
                messageHelper.getMessage(MessageConstants.ERROR_INVALID_PAGE_INDEX_OR_SIZE));

        final SortOrder sortOrder = logFilter.getSortOrder() != null
                ? SortOrder.fromString(logFilter.getSortOrder())
                : SortOrder.DESC;
        final SearchSourceBuilder source = new SearchSourceBuilder()
                .query(constructQueryFilter(logFilter))
                .sort(MESSAGE_TIMESTAMP, sortOrder)
                .sort(ID, sortOrder)
                .size(pagination.getPageSize() + 1);

        final PageMarker pageMarker = logFilter.getPagination().getToken();
        if (pageMarker != null) {
            Assert.isTrue(pageMarker.getId() != null && pageMarker.getMessageTimestamp() != null,
                    "Token should contain id and messageTimestamp values");
            source.searchAfter(
                    new Object[]{
                            pageMarker.getMessageTimestamp()
                                    .toInstant(ZoneOffset.UTC).toEpochMilli(),
                            pageMarker.getId()
                    }
            );
        }

        final SearchRequest request = new SearchRequest()
                .source(source)
                .indices(getLogIndices(logFilter.getMessageTimestampFrom(), logFilter.getMessageTimestampTo()))
                .indicesOptions(INDICES_OPTIONS);
        log.debug("Logs request: {} ", request);

        final SearchResponse response = verifyResponse(executeRequest(request));
        final SearchHits hits = response.getHits();

        final List<LogEntry> entries = Arrays.stream(hits.getHits())
                .map(this::mapHitToLogEntry)
                .collect(Collectors.toList());

        return LogPagination.builder()
                .logEntries(entries.stream().limit(pagination.getPageSize()).collect(Collectors.toList()))
                .pageSize(pagination.getPageSize())
                .token(getToken(entries, pagination.getPageSize()))
                .totalHits(hits.totalHits)
                .build();
    }

    private String[] getLogIndices(final LocalDateTime from, final LocalDateTime to) {
        final LocalDate toDate = Optional.ofNullable(to)
                .orElseGet(DateUtils::nowUTC)
                .toLocalDate();
        return Optional.ofNullable(from)
                .map(LocalDateTime::toLocalDate)
                .map(fromDate -> getLogDayIndices(fromDate, toDate))
                .orElseGet(this::getLogIndices);
    }

    private String[] getLogIndices() {
        return new String[]{getIndexName(indexPrefix, ES_WILDCARD)};
    }

    /**
     * Returns system logs day indices taking into consideration filebeat transition period.
     *
     * The transition period includes adjacent indices which may contain required documents.
     */
    private String[] getLogDayIndices(final LocalDate from, final LocalDate to) {
        final LocalDate actualFrom = from.minus(FILEBEAT_TRANSITION_PERIOD);
        final LocalDate actualTo = to.plus(FILEBEAT_TRANSITION_PERIOD);
        return Stream.iterate(actualFrom, date -> date.plusDays(1))
                .limit(Period.between(actualFrom, actualTo).getDays() + 1)
                .map(date -> date.format(ELASTIC_DATE_FORMATTER))
                .map(dateString -> getIndexName(indexPrefix, dateString, ES_WILDCARD))
                .toArray(String[]::new);
    }

    private String getIndexName(final String... args) {
        return String.join("-", args);
    }

    private PageMarker getToken(final List<LogEntry> items, final int pageSize) {
        if (items == null || items.size() <= pageSize) {
            return null;
        }
        final LogEntry entry = items.get(items.size() - 1);
        return new PageMarker(entry.getEventId(), entry.getMessageTimestamp());
    }

    private BoolQueryBuilder constructQueryFilter(final LogFilter logFilter) {
        final BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();

        if (BooleanUtils.isNotTrue(logFilter.getIncludeServiceAccountEvents())) {
            boolQuery.filter(QueryBuilders.termQuery(SERVICE_ACCOUNT, false));
        }

        if (CollectionUtils.isNotEmpty(logFilter.getUsers())) {
            List<String> formattedUsers = logFilter.getUsers().stream()
                    .flatMap(user -> Stream.of(user.toLowerCase(), user.toUpperCase()))
                    .collect(Collectors.toList());
            boolQuery.filter(QueryBuilders.termsQuery(USER, formattedUsers));
        }
        if (CollectionUtils.isNotEmpty(logFilter.getHostnames())) {
            boolQuery.filter(QueryBuilders.termsQuery(HOSTNAME + KEYWORD, logFilter.getHostnames()));
        }
        if (CollectionUtils.isNotEmpty(logFilter.getServiceNames())) {
            boolQuery.filter(QueryBuilders.termsQuery(SERVICE_NAME + KEYWORD, logFilter.getServiceNames()));
        }
        if (CollectionUtils.isNotEmpty(logFilter.getTypes())) {
            boolQuery.filter(QueryBuilders.termsQuery(TYPE + KEYWORD, logFilter.getTypes()));
        }
        if (StringUtils.isNotEmpty(logFilter.getMessage())) {
            boolQuery.filter(QueryBuilders.matchQuery(MESSAGE, logFilter.getMessage()));
        }

        addRangeFilter(boolQuery, logFilter.getMessageTimestampFrom(),
                logFilter.getMessageTimestampTo());
        return boolQuery;
    }

    private void addRangeFilter(final BoolQueryBuilder boolQuery,
                                final LocalDateTime from, final LocalDateTime to) {
        if (from == null && to == null) {
            return;
        }

        final RangeQueryBuilder rangeQueryBuilder = QueryBuilders.rangeQuery(MESSAGE_TIMESTAMP);

        if (from != null) {
            rangeQueryBuilder.from(from.toInstant(ZoneOffset.UTC));
        }
        if (to != null) {
            rangeQueryBuilder.to(to.toInstant(ZoneOffset.UTC));
        }

        boolQuery.filter(rangeQueryBuilder);
    }

    private SearchResponse verifyResponse(final SearchResponse logsResponse) {
        if (logsResponse.status().getStatus() != HttpStatus.OK.value()) {
            throw new IllegalStateException(String.format("Search request has failed with HTTP status %s (%s).",
                    logsResponse.status().name(), logsResponse.status().getStatus()));
        }

        log.debug("Search request has finished with {} successful, {} skipped and {} failed shards of {} total.",
                logsResponse.getSuccessfulShards(), logsResponse.getSkippedShards(),
                logsResponse.getFailedShards(), logsResponse.getTotalShards());
        final ShardSearchFailure[] failures = logsResponse.getShardFailures();
        if (failures.length > 0) {
            final List<Throwable> errors = Arrays.stream(failures)
                    .map(ShardSearchFailure::getCause)
                    .collect(Collectors.toList());
            final String errorMessages = errors.stream()
                    .map(Throwable::getMessage)
                    .collect(Collectors.joining("\n"));
            log.error("Search request has finished with the following shard failures: {}", errorMessages);
            throw new IllegalStateException("Search request has failed because some shards failed. ",
                    failures[0].getCause());
        }

        return logsResponse;
    }

    private LogEntry mapHitToLogEntry(SearchHit searchHit) {
        Map<String, Object> hit = searchHit.getSourceAsMap();
        return LogEntry.builder()
                .eventId((Long) hit.get(ID))
                .messageTimestamp(LocalDateTime.parse((String) hit.get(MESSAGE_TIMESTAMP), DATE_TIME_FORMATTER))
                .hostname((String) hit.get(HOSTNAME))
                .serviceName((String) hit.get(SERVICE_NAME))
                .type((String) hit.get(TYPE))
                .user((String) hit.get(USER))
                .message((String) hit.get(MESSAGE))
                .severity((String) hit.getOrDefault(SEVERITY, DEFAULT_SEVERITY))
                .build();
    }

    private SearchResponse executeRequest(final SearchRequest searchRequest) {
        try (RestHighLevelClient client = elasticHelper.buildClient()) {
            return client.search(searchRequest, RequestOptions.DEFAULT);
        } catch (IOException e) {
            throw new PipelineException(e);
        }
    }

    public LogFilter getFilters() {
        final LogFilter result = new LogFilter();
        final SearchSourceBuilder source = new SearchSourceBuilder()
                .query(QueryBuilders.boolQuery())
                .size(0)
                .aggregation(AggregationBuilders.terms(TYPE).field(TYPE + KEYWORD))
                .aggregation(AggregationBuilders.terms(SERVICE_NAME)
                        .field(SERVICE_NAME + KEYWORD))
                .aggregation(AggregationBuilders.terms(HOSTNAME).field(HOSTNAME + KEYWORD));
        final SearchRequest request = new SearchRequest()
                .source(source)
                .indices(getLogIndices())
                .indicesOptions(INDICES_OPTIONS);
        log.debug("Logs request: {} ", request);

        final SearchResponse response = verifyResponse(executeRequest(request));
        response.getAggregations()
                .asList()
                .stream()
                .map(Terms.class::cast)
                .forEach(terms -> {
                    List<String> values = terms.getBuckets().stream()
                            .map(MultiBucketsAggregation.Bucket::getKey)
                            .map(Object::toString)
                            .collect(Collectors.toList());

                    if (HOSTNAME.equals(terms.getName())) {
                        result.setHostnames(values);
                    } else if (SERVICE_NAME.equals(terms.getName())) {
                        result.setServiceNames(values);
                    } else if (TYPE.equals(terms.getName())) {
                        result.setTypes(values);
                    }
                });

        return result;
    }
}
