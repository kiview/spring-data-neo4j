/*
 * Copyright 2011-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.neo4j.core;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Query;
import org.neo4j.driver.QueryRunner;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.neo4j.driver.summary.ResultSummary;
import org.neo4j.driver.types.TypeSystem;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.converter.ConverterRegistry;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.support.PersistenceExceptionTranslator;
import org.springframework.data.neo4j.core.convert.Neo4jConversions;
import org.springframework.data.neo4j.core.transaction.Neo4jTransactionManager;
import org.springframework.data.neo4j.core.transaction.Neo4jTransactionUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Default implementation of {@link Neo4jClient}. Uses the Neo4j Java driver to connect to and interact with the
 * database. TODO Micrometer hooks for statement results...
 *
 * @author Gerrit Meier
 * @author Michael J. Simons
 * @since 6.0
 */
class DefaultNeo4jClient implements Neo4jClient {

	private final Driver driver;
	private final TypeSystem typeSystem;
	private final DatabaseSelectionProvider databaseSelectionProvider;
	private final ConversionService conversionService;
	private final Neo4jPersistenceExceptionTranslator persistenceExceptionTranslator = new Neo4jPersistenceExceptionTranslator();

	DefaultNeo4jClient(Driver driver, DatabaseSelectionProvider databaseSelectionProvider) {

		this.driver = driver;
		this.typeSystem = driver.defaultTypeSystem();
		this.databaseSelectionProvider = databaseSelectionProvider;

		this.conversionService = new DefaultConversionService();
		new Neo4jConversions().registerConvertersIn((ConverterRegistry) conversionService);
	}

	DelegatingQueryRunner getQueryRunner(@Nullable final String targetDatabase) {

		QueryRunner queryRunner = Neo4jTransactionManager.retrieveTransaction(driver, targetDatabase);
		if (queryRunner == null) {
			queryRunner = driver.session(Neo4jTransactionUtils.defaultSessionConfig(targetDatabase));
		}

		return new DelegatingQueryRunner(queryRunner);
	}

	static class DelegatingQueryRunner implements QueryRunner, AutoCloseable {

		private final QueryRunner delegate;

		DelegatingQueryRunner(QueryRunner delegate) {
			this.delegate = delegate;
		}

		@Override
		public void close() {

			// We're only going to close sessions we have acquired inside the client, not something that
			// has been retrieved from the tx manager.
			if (this.delegate instanceof Session) {
				((Session) this.delegate).close();
			}
		}

		@Override
		public Result run(String s, Value value) {
			return delegate.run(s, value);
		}

		@Override
		public Result run(String s, Map<String, Object> map) {
			return delegate.run(s, map);
		}

		@Override
		public Result run(String s, Record record) {
			return delegate.run(s, record);
		}

		@Override
		public Result run(String s) {
			return delegate.run(s);
		}

		@Override
		public Result run(Query query) {
			return delegate.run(query);
		}
	}

	// Below are all the implementations (methods and classes) as defined by the contracts of Neo4jClient

	@Override
	public RunnableSpec query(String cypher) {
		return query(() -> cypher);
	}

	@Override
	public RunnableSpec query(Supplier<String> cypherSupplier) {
		return new DefaultRunnableSpec(cypherSupplier);
	}

	@Override
	public <T> OngoingDelegation<T> delegateTo(Function<QueryRunner, Optional<T>> callback) {
		return new DefaultRunnableDelegation<>(callback);
	}

	@Override
	public DatabaseSelectionProvider getDatabaseSelectionProvider() {
		return databaseSelectionProvider;
	}

	/**
	 * Basically a holder of a cypher template supplier and a set of named parameters. It's main purpose is to orchestrate
	 * the running of things with a bit of logging.
	 */
	class RunnableStatement {

		RunnableStatement(Supplier<String> cypherSupplier) {
			this(cypherSupplier, new NamedParameters());
		}

		RunnableStatement(Supplier<String> cypherSupplier, NamedParameters parameters) {
			this.cypherSupplier = cypherSupplier;
			this.parameters = parameters;
		}

		private final Supplier<String> cypherSupplier;

		private final NamedParameters parameters;

		protected final Result runWith(QueryRunner statementRunner) {
			String statementTemplate = cypherSupplier.get();

			if (cypherLog.isDebugEnabled()) {
				cypherLog.debug(() -> String.format("Executing:%s%s", System.lineSeparator(), statementTemplate));

				if (cypherLog.isTraceEnabled() && !parameters.isEmpty()) {
					cypherLog.trace(() -> String.format("with parameters:%s%s", System.lineSeparator(), parameters));
				}
			}

			return statementRunner.run(statementTemplate, parameters.get());
		}
	}

	/**
	 * Tries to convert the given {@link RuntimeException} into a {@link DataAccessException} but returns the original
	 * exception if the conversation failed. Thus allows safe re-throwing of the return value.
	 *
	 * @param ex                  the exception to translate
	 * @param exceptionTranslator the {@link PersistenceExceptionTranslator} to be used for translation
	 * @return
	 */
	private static RuntimeException potentiallyConvertRuntimeException(RuntimeException ex,
			PersistenceExceptionTranslator exceptionTranslator) {
		RuntimeException resolved = exceptionTranslator.translateExceptionIfPossible(ex);
		return resolved == null ? ex : resolved;
	}

	class DefaultRunnableSpec implements RunnableSpec {

		private RunnableStatement runnableStatement;

		private String targetDatabase;

		DefaultRunnableSpec(Supplier<String> cypherSupplier) {
			this.targetDatabase = Neo4jClient.verifyDatabaseName(resolveTargetDatabaseName(targetDatabase));
			this.runnableStatement = new RunnableStatement(cypherSupplier);
		}

		@Override
		public RunnableSpecTightToDatabase in(@SuppressWarnings("HiddenField") String targetDatabase) {

			this.targetDatabase = Neo4jClient.verifyDatabaseName(targetDatabase);
			return this;
		}

		class DefaultOngoingBindSpec<T> implements OngoingBindSpec<T, RunnableSpecTightToDatabase> {

			@Nullable private final T value;

			DefaultOngoingBindSpec(@Nullable T value) {
				this.value = value;
			}

			@Override
			public RunnableSpecTightToDatabase to(String name) {

				DefaultRunnableSpec.this.runnableStatement.parameters.add(name, value);
				return DefaultRunnableSpec.this;
			}

			@Override
			public RunnableSpecTightToDatabase with(Function<T, Map<String, Object>> binder) {

				Assert.notNull(binder, "Binder is required.");

				return bindAll(binder.apply(value));
			}
		}

		@Override
		public OngoingBindSpec<?, RunnableSpecTightToDatabase> bind(@Nullable Object value) {
			return new DefaultOngoingBindSpec(value);
		}

		@Override
		public RunnableSpecTightToDatabase bindAll(Map<String, Object> newParameters) {
			this.runnableStatement.parameters.addAll(newParameters);
			return this;
		}

		@Override
		public <T> MappingSpec<T> fetchAs(Class<T> targetClass) {

			return new DefaultRecordFetchSpec(this.targetDatabase, this.runnableStatement,
					new SingleValueMappingFunction(conversionService, targetClass));
		}

		@Override
		public RecordFetchSpec<Map<String, Object>> fetch() {

			return new DefaultRecordFetchSpec<>(this.targetDatabase, this.runnableStatement, (t, r) -> r.asMap());
		}

		@Override
		public ResultSummary run() {

			try (DelegatingQueryRunner statementRunner = getQueryRunner(this.targetDatabase)) {
				Result result = runnableStatement.runWith(statementRunner);
				return ResultSummaries.process(result.consume());
			} catch (RuntimeException e) {
				throw potentiallyConvertRuntimeException(e, persistenceExceptionTranslator);
			}
		}

		private String resolveTargetDatabaseName(@Nullable String parameterTargetDatabase) {
			if (parameterTargetDatabase != null) {
				return parameterTargetDatabase;
			}
			if (databaseSelectionProvider != null) {
				String databaseSelectionProviderValue = databaseSelectionProvider.getDatabaseSelection().getValue();
				if (databaseSelectionProviderValue != null) {
					return databaseSelectionProviderValue;
				}
			}
			return DatabaseSelectionProvider.getDefaultSelectionProvider().getDatabaseSelection().getValue();
		}
	}

	class DefaultRecordFetchSpec<T> implements RecordFetchSpec<T>, MappingSpec<T> {

		private final String targetDatabase;

		private final RunnableStatement runnableStatement;

		private BiFunction<TypeSystem, Record, T> mappingFunction;

		DefaultRecordFetchSpec(String parameterTargetDatabase, RunnableStatement runnableStatement,
				BiFunction<TypeSystem, Record, T> mappingFunction) {

			this.targetDatabase = parameterTargetDatabase;
			this.runnableStatement = runnableStatement;
			this.mappingFunction = mappingFunction;
		}

		@Override
		public RecordFetchSpec<T> mappedBy(
				@SuppressWarnings("HiddenField") BiFunction<TypeSystem, Record, T> mappingFunction) {

			this.mappingFunction = new DelegatingMappingFunctionWithNullCheck<>(mappingFunction);
			return this;
		}

		@Override
		public Optional<T> one() {

			try (DelegatingQueryRunner statementRunner = getQueryRunner(this.targetDatabase)) {
				Result result = runnableStatement.runWith(statementRunner);
				Optional<T> optionalValue = result.hasNext() ?
						Optional.ofNullable(mappingFunction.apply(typeSystem, result.single())) :
						Optional.empty();
				ResultSummaries.process(result.consume());
				return optionalValue;
			} catch (RuntimeException e) {
				throw potentiallyConvertRuntimeException(e, persistenceExceptionTranslator);
			}
		}

		@Override
		public Optional<T> first() {

			try (DelegatingQueryRunner statementRunner = getQueryRunner(this.targetDatabase)) {
				Result result = runnableStatement.runWith(statementRunner);
				Optional<T> optionalValue = result.stream().map(partialMappingFunction(typeSystem)).findFirst();
				ResultSummaries.process(result.consume());
				return optionalValue;
			} catch (RuntimeException e) {
				throw potentiallyConvertRuntimeException(e, persistenceExceptionTranslator);
			}
		}

		@Override
		public Collection<T> all() {

			try (DelegatingQueryRunner statementRunner = getQueryRunner(this.targetDatabase)) {
				Result result = runnableStatement.runWith(statementRunner);
				Collection<T> values = result.stream().map(partialMappingFunction(typeSystem)).collect(Collectors.toList());
				ResultSummaries.process(result.consume());
				return values;
			} catch (RuntimeException e) {
				throw potentiallyConvertRuntimeException(e, persistenceExceptionTranslator);
			}
		}

		/**
		 * @param typeSystem The actual type system
		 * @return The partially evaluated mapping function
		 */
		private Function<Record, T> partialMappingFunction(TypeSystem typeSystem) {
			return r -> mappingFunction.apply(typeSystem, r);
		}
	}

	class DefaultRunnableDelegation<T> implements RunnableDelegation<T>, OngoingDelegation<T> {

		private final Function<QueryRunner, Optional<T>> callback;

		@Nullable private String targetDatabase;

		DefaultRunnableDelegation(Function<QueryRunner, Optional<T>> callback) {
			this(callback, null);
		}

		DefaultRunnableDelegation(Function<QueryRunner, Optional<T>> callback, @Nullable String targetDatabase) {
			this.callback = callback;
			this.targetDatabase = Neo4jClient.verifyDatabaseName(targetDatabase);
		}

		@Override
		public RunnableDelegation in(@Nullable @SuppressWarnings("HiddenField") String targetDatabase) {

			this.targetDatabase = Neo4jClient.verifyDatabaseName(targetDatabase);
			return this;
		}

		@Override
		public Optional<T> run() {
			try (DelegatingQueryRunner queryRunner = getQueryRunner(targetDatabase)) {
				return callback.apply(queryRunner);
			} catch (RuntimeException e) {
				throw potentiallyConvertRuntimeException(e, persistenceExceptionTranslator);
			}
		}
	}

}
