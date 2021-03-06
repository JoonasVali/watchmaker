//=============================================================================
// Copyright 2006-2010 Daniel W. Dyer
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//=============================================================================
package org.uncommons.watchmaker.framework;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Base class for {@link EvolutionEngine} implementations.
 *
 * @param <T> The type of entity evolved by the evolution engine.
 * @author Daniel Dyer
 * @see CandidateFactory
 * @see FitnessEvaluator
 */
public abstract class AbstractEvolutionEngine<T> implements EvolutionEngine<T> {
  // A single multi-threaded worker is shared among multiple evolution engine instances.
  private static AtomicReference<FitnessEvaluationWorker> concurrentWorker = new AtomicReference<>();

  private final Set<EvolutionObserver<? super T>> observers = new CopyOnWriteArraySet<>();

  private final Random rng;
  private final CandidateFactory<T> candidateFactory;
  private final FitnessEvaluator<? super T> fitnessEvaluator;

  private volatile boolean singleThreaded = false;

  private List<TerminationCondition> satisfiedTerminationConditions;


  /**
   * Creates a new evolution engine by specifying the various components required by
   * an evolutionary algorithm.
   *
   * @param candidateFactory Factory used to create the initial population that is
   *                         iteratively evolved.
   * @param fitnessEvaluator A function for assigning fitness scores to candidate
   *                         solutions.
   * @param rng              The source of randomness used by all stochastic processes (including
   *                         evolutionary operators and selection strategies).
   */
  protected AbstractEvolutionEngine(CandidateFactory<T> candidateFactory,
                                    FitnessEvaluator<? super T> fitnessEvaluator,
                                    Random rng) {
    this.candidateFactory = candidateFactory;
    this.fitnessEvaluator = fitnessEvaluator;
    this.rng = rng;
  }


  /**
   * {@inheritDoc}
   */
  public T evolve(int populationSize,
                  int eliteCount,
                  TerminationCondition... conditions) {
    return evolve(populationSize,
        eliteCount,
        Collections.emptySet(),
        conditions);
  }


  /**
   * {@inheritDoc}
   */
  public T evolve(int populationSize,
                  int eliteCount,
                  Collection<T> seedCandidates,
                  TerminationCondition... conditions) {
    return evolvePopulation(populationSize,
        eliteCount,
        seedCandidates,
        conditions).get(0).getCandidate();
  }


  /**
   * {@inheritDoc}
   */
  public List<EvaluatedCandidate<T>> evolvePopulation(int populationSize,
                                                      int eliteCount,
                                                      TerminationCondition... conditions) {
    return evolvePopulation(populationSize,
        eliteCount,
        Collections.emptySet(),
        conditions);
  }


  /**
   * {@inheritDoc}
   */
  public List<EvaluatedCandidate<T>> evolvePopulation(int populationSize,
                                                      int eliteCount,
                                                      Collection<T> seedCandidates,
                                                      TerminationCondition... conditions) {
    if (eliteCount < 0 || eliteCount >= populationSize) {
      throw new IllegalArgumentException("Elite count must be non-negative and less than population size.");
    }
    if (conditions.length == 0) {
      throw new IllegalArgumentException("At least one TerminationCondition must be specified.");
    }

    satisfiedTerminationConditions = null;
    int currentGenerationIndex = 0;
    long startTime = System.currentTimeMillis();

    List<T> population = candidateFactory.generateInitialPopulation(populationSize,
        seedCandidates,
        rng);

    // Calculate the fitness scores for each member of the initial population.
    List<EvaluatedCandidate<T>> evaluatedPopulation = evaluatePopulation(population);
    EvolutionUtils.sortEvaluatedPopulation(evaluatedPopulation, fitnessEvaluator.isNatural());
    PopulationData<T> data = EvolutionUtils.getPopulationData(evaluatedPopulation,
        fitnessEvaluator.isNatural(),
        eliteCount,
        currentGenerationIndex,
        startTime);
    // Notify observers of the state of the population.
    notifyPopulationChange(data);

    List<TerminationCondition> satisfiedConditions = EvolutionUtils.shouldContinue(data, conditions);
    while (satisfiedConditions == null) {
      ++currentGenerationIndex;
      evaluatedPopulation = nextEvolutionStep(evaluatedPopulation, eliteCount, rng);
      EvolutionUtils.sortEvaluatedPopulation(evaluatedPopulation, fitnessEvaluator.isNatural());
      data = EvolutionUtils.getPopulationData(evaluatedPopulation,
          fitnessEvaluator.isNatural(),
          eliteCount,
          currentGenerationIndex,
          startTime);
      // Notify observers of the state of the population.
      notifyPopulationChange(data);
      satisfiedConditions = EvolutionUtils.shouldContinue(data, conditions);
    }
    this.satisfiedTerminationConditions = satisfiedConditions;
    return evaluatedPopulation;
  }


  /**
   * This method performs a single step/iteration of the evolutionary process.
   *
   * @param evaluatedPopulation The population at the beginning of the process.
   * @param eliteCount          The number of the fittest individuals that must be preserved.
   * @param rng                 A source of randomness.
   * @return The updated population after the evolutionary process has proceeded
   * by one step/iteration.
   */
  protected abstract List<EvaluatedCandidate<T>> nextEvolutionStep(List<EvaluatedCandidate<T>> evaluatedPopulation,
                                                                   int eliteCount,
                                                                   Random rng);


  /**
   * Takes a population, assigns a fitness score to each member and returns
   * the members with their scores attached, sorted in descending order of
   * fitness (descending order of fitness score for natural scores, ascending
   * order of scores for non-natural scores).
   *
   * @param population The population to evaluate (each candidate is assigned
   *                   a fitness score).
   * @return The evaluated population (a list of candidates with attached fitness
   * scores).
   */
  protected List<EvaluatedCandidate<T>> evaluatePopulation(List<T> population) {
    List<EvaluatedCandidate<T>> evaluatedPopulation;

    if (singleThreaded) { // Do fitness evaluations on the request thread.
      evaluatedPopulation = population.stream()
          .map(candidate -> new EvaluatedCandidate<>(candidate, fitnessEvaluator.getFitness(candidate, population)))
          .collect(Collectors.toList());
    } else {
      // Divide the required number of fitness evaluations equally among the
      // available processors and coordinate the threads so that we do not
      // proceed until all threads have finished processing.
      List<T> unmodifiablePopulation = Collections.unmodifiableList(population);

      // Submit tasks for execution and wait until all threads have finished fitness evaluations.
      List<Future<EvaluatedCandidate<T>>> results =
          population.stream()
              .map(candidate ->
                  getSharedWorker().submit(
                      new FitnessEvalutationTask<>(fitnessEvaluator, candidate, unmodifiablePopulation)
                  )
              ).collect(Collectors.toList());

      evaluatedPopulation = results.stream().map(result -> {
        try {
          return result.get();
        } catch (InterruptedException e) {
          // Restore the interrupted status, allows methods further up the call-stack
          // to abort processing if appropriate.
          Thread.currentThread().interrupt();
          return null;
        } catch (ExecutionException e) {
          throw new IllegalStateException("Fitness evaluation task execution failed.", e);
        }
      }).collect(Collectors.toList());
    }

    return evaluatedPopulation;
  }


  /**
   * <p>Returns a list of all {@link TerminationCondition}s that are satisfied by the current
   * state of the evolution engine.  Usually this list will contain only one item, but it
   * is possible that mutliple termination conditions will become satisfied at the same
   * time.  In this case the condition objects in the list will be in the same order that
   * they were specified when passed to the engine.</p>
   *
   * <p>If the evolution has not yet terminated (either because it is still in progress or
   * because it hasn't even been started) then an IllegalStateException will be thrown.</p>
   *
   * <p>If the evolution terminated because the request thread was interrupted before any
   * termination conditions were satisfied then this method will return an empty list.</p>
   *
   * @return A list of statisfied conditions.  The list is guaranteed to be non-null.  The
   * list may be empty because it is possible for evolution to terminate without any conditions
   * being matched.  The only situation in which this occurs is when the request thread is
   * interrupted.
   * @throws IllegalStateException If this method is invoked on an evolution engine before
   *                               evolution is started or while it is still in progress.
   */
  public List<TerminationCondition> getSatisfiedTerminationConditions() {
    if (satisfiedTerminationConditions == null) {
      throw new IllegalStateException("EvolutionEngine has not terminated.");
    } else {
      return Collections.unmodifiableList(satisfiedTerminationConditions);
    }
  }


  /**
   * Adds a listener to receive status updates on the evolution progress.
   * Updates are dispatched synchronously on the request thread.  Observers should
   * complete their processing and return in a timely manner to avoid holding up
   * the evolution.
   *
   * @param observer An evolution observer call-back.
   * @see #removeEvolutionObserver(EvolutionObserver)
   */
  public void addEvolutionObserver(EvolutionObserver<? super T> observer) {
    observers.add(observer);
  }


  /**
   * Removes an evolution progress listener.
   *
   * @param observer An evolution observer call-back.
   * @see #addEvolutionObserver(EvolutionObserver)
   */
  public void removeEvolutionObserver(EvolutionObserver<? super T> observer) {
    observers.remove(observer);
  }


  /**
   * Send the population data to all registered observers.
   *
   * @param data Information about the current state of the population.
   */
  private void notifyPopulationChange(PopulationData<T> data) {
    observers.forEach(obs -> obs.populationUpdate(data));
  }


  /**
   * By default, fitness evaluations are performed on separate threads (as many as there are
   * available cores/processors).  Use this method to force evaluation to occur synchronously
   * on the request thread.  This is useful in restricted environments where programs are not
   * permitted to start or control threads.  It might also lead to better performance for
   * programs that have extremely lightweight/trivial fitness evaluations.
   *
   * @param singleThreaded If true, fitness evaluations will be performed synchronously on the
   *                       request thread.  If false, fitness evaluations will be performed by worker threads.
   */
  public void setSingleThreaded(boolean singleThreaded) {
    this.singleThreaded = singleThreaded;
  }


  /**
   * Lazily create the multi-threaded worker for fitness evaluations.
   */
  private static FitnessEvaluationWorker getSharedWorker() {
    FitnessEvaluationWorker worker = concurrentWorker.get();
    if (worker == null) {
      worker = new FitnessEvaluationWorker();
      if (!concurrentWorker.compareAndSet(null, worker)) {
        worker.shutdown();
        return concurrentWorker.get();
      }
    }
    return worker;
  }
}
