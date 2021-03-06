package org.aksw.autosparql.algorithm.tbsl.learning;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.aksw.autosparql.algorithm.tbsl.graph.Template2GraphConverter;
import org.aksw.autosparql.algorithm.tbsl.learning.ranking.Ranking;
import org.aksw.autosparql.algorithm.tbsl.learning.ranking.RankingComputation;
import org.aksw.autosparql.algorithm.tbsl.learning.ranking.SimpleRankingComputation;
import org.aksw.autosparql.algorithm.tbsl.sparql.Slot;
import org.aksw.autosparql.algorithm.tbsl.sparql.Template;
import org.aksw.autosparql.algorithm.tbsl.templator.Templator;
import org.aksw.autosparql.algorithm.tbsl.util.Knowledgebase;
import org.aksw.autosparql.algorithm.tbsl.util.LocalKnowledgebase;
import org.aksw.autosparql.algorithm.tbsl.util.RemoteKnowledgebase;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.SimpleLayout;
import org.dllearner.common.index.HierarchicalIndex;
import org.dllearner.common.index.Index;
import org.dllearner.common.index.SOLRIndex;
import org.dllearner.kb.LocalModelBasedSparqlEndpointKS;
import org.dllearner.kb.SparqlEndpointKS;
import org.dllearner.kb.sparql.ExtractionDBCache;
import org.dllearner.kb.sparql.SparqlEndpoint;
import org.dllearner.kb.sparql.SparqlQuery;
import org.dllearner.reasoning.SPARQLReasoner;
import org.ini4j.InvalidFileFormatException;
import org.ini4j.Options;
import org.aksw.autosparql.commons.nlp.lemma.Lemmatizer;
import org.aksw.autosparql.commons.nlp.lemma.LingPipeLemmatizer;
import org.aksw.autosparql.commons.nlp.pos.PartOfSpeechTagger;
import org.aksw.autosparql.commons.nlp.pos.StanfordPartOfSpeechTagger;
import org.aksw.autosparql.commons.nlp.wordnet.WordNet;
import com.hp.hpl.jena.query.QueryExecutionFactory;
import com.hp.hpl.jena.query.QueryFactory;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.query.Syntax;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.sparql.engine.http.QueryEngineHTTP;
import com.jamonapi.Monitor;
import com.jamonapi.MonitorFactory;

public class TBSL {
	
	enum Mode{
		BEST_QUERY, BEST_NON_EMPTY_QUERY
	}
	
	private Mode mode = Mode.BEST_QUERY;
	
	private static final Logger logger = Logger.getLogger(TBSL.class);
	private Monitor monitor = MonitorFactory.getTimeMonitor("tbsl");
	
	private boolean useRemoteEndpointValidation;
	private boolean stopIfQueryResultNotEmpty;
	private int maxTestedQueriesPerTemplate = 50;
	private int maxQueryExecutionTimeInSeconds;
	private int maxTestedQueries = 200;
	private int maxIndexResults;
	
	private SparqlEndpoint endpoint;
	private Model model;
	private Knowledgebase knowledgebase;
	
	private ExtractionDBCache cache = new ExtractionDBCache("cache");
	
	private Templator templateGenerator;
	private Lemmatizer lemmatizer;
	private PartOfSpeechTagger posTagger;
	private WordNet wordNet;
	
	private Set<Template> templates;
	
	private SPARQLReasoner reasoner;
	
	private String [] grammarFiles = new String[]{"tbsl/lexicon/english.lex"};
	
	
	private Set<String> relevantKeywords;
	
	private static final String DEFAULT_WORDNET_PROPERTIES_FILE = "tbsl/wordnet_properties.xml";
	
	public TBSL(Knowledgebase knowledgebase){
		this(knowledgebase, new StanfordPartOfSpeechTagger(), new WordNet(), new Options(), null);
		//this(knowledgebase, new StanfordPartOfSpeechTagger(), new WordNet(DEFAULT_WORDNET_PROPERTIES_FILE), new Options(), null);
	}
	
	public TBSL(Knowledgebase knowledgebase, PartOfSpeechTagger posTagger, WordNet wordNet, Options options){
		this(knowledgebase, posTagger, wordNet, options, null);
	}
	
	public TBSL(Knowledgebase knowledgebase, WordNet wordNet){
		this(knowledgebase, new StanfordPartOfSpeechTagger(), wordNet, new Options(), null);
	}
	
	public TBSL(Knowledgebase knowledgebase, PartOfSpeechTagger posTagger, WordNet wordNet, Options options, ExtractionDBCache cache){
		this.knowledgebase = knowledgebase;
		this.posTagger = posTagger;
		this.wordNet = wordNet;
		this.cache = cache;
		
		SparqlEndpointKS ks;
		if(knowledgebase instanceof RemoteKnowledgebase){
			ks = new SparqlEndpointKS(((RemoteKnowledgebase) knowledgebase).getEndpoint());
		} else {
			ks = new LocalModelBasedSparqlEndpointKS(((LocalKnowledgebase) knowledgebase).getModel());
		}
		reasoner = new SPARQLReasoner(ks);
		reasoner.setCache(cache);
//		reasoner.prepareSubsumptionHierarchy();
		setOptions(options);
	}
	
	public void setGrammarFiles(String[] grammarFiles){
		templateGenerator.setGrammarFiles(grammarFiles);
	}
	
	public void init() {
		 templateGenerator = new Templator(posTagger, wordNet, grammarFiles);
		 lemmatizer = new LingPipeLemmatizer();
	}
	
	/*
	 * Only for Evaluation useful.
	 */
	public void setUseIdealTagger(boolean value){
		templateGenerator.setUNTAGGED_INPUT(!value);
	}
	
	private void setOptions(Options options){
		maxIndexResults = Integer.parseInt(options.get("solr.query.limit", "10"));
		
		maxQueryExecutionTimeInSeconds = Integer.parseInt(options.get("sparql.query.maxExecutionTimeInSeconds", "100"));
		if(cache != null){
			cache.setMaxExecutionTimeInSeconds(maxQueryExecutionTimeInSeconds);
		}
		
		useRemoteEndpointValidation = options.get("learning.validationType", "remote").equals("remote") ? true : false;
		stopIfQueryResultNotEmpty = Boolean.parseBoolean(options.get("learning.stopAfterFirstNonEmptyQueryResult", "true"));
		maxTestedQueriesPerTemplate = Integer.parseInt(options.get("learning.maxTestedQueriesPerTemplate", "20"));
		
		String wordnetPath = options.get("wordnet.dictionary", "tbsl/dict");
		wordnetPath = this.getClass().getClassLoader().getResource(wordnetPath).getPath();
		System.setProperty("wordnet.database.dir", wordnetPath);
	}
	
	private void reset(){
		
	}
	
	public TemplateInstantiation answerQuestion(String question) throws NoTemplateFoundException
	{return answerQuestion(question,Collections.<Double>emptyList());}
	
	public TemplateInstantiation answerQuestion(String question, List<Double> parameters) throws NoTemplateFoundException{
		reset();
		
		//1. Generate SPARQL query templates
		logger.debug("Running template generation...");
		monitor.start();
		templates = templateGenerator.buildTemplates(question);
		monitor.stop();
		logger.debug("Done in " + monitor.getLastValue() + "ms.");
		if(templates.isEmpty()){
			throw new NoTemplateFoundException();
		}
		logger.debug("Generated " + templates.size() + " templates:");
		for(Template t : templates){
			logger.debug(t);
		}
		//1.b filter out invalid templates
		filterTemplates(templates);
		
		//2. Entity URI Disambiguation
		logger.debug("Running entity disambiguation...");
		monitor.start();
		SimpleEntityDisambiguation entityDisambiguation = new SimpleEntityDisambiguation(knowledgebase);
		Map<Template, Map<Slot, Collection<Entity>>> template2Allocations = entityDisambiguation.performEntityDisambiguation(templates);
		monitor.stop();
		logger.debug("Done in " + monitor.getLastValue() + "ms.");
		
		//3. Generate possible instantiations of the templates, i.e. find entities for the slots
		logger.debug("Running template instantiation...");
		monitor.start();
		Map<Template, List<TemplateInstantiation>> template2Instantiations = instantiateTemplates(template2Allocations);
		monitor.stop();
		logger.debug("Done in " + monitor.getLastValue() + "ms.");
		
		//4. Rank the template instantiations
		logger.debug("Running ranking...");
		monitor.start();
		SimpleRankingComputation rankingComputation = new SimpleRankingComputation(knowledgebase);
		Ranking ranking = rankingComputation.computeRanking(template2Instantiations, template2Allocations, parameters);
		monitor.stop();
		logger.debug("Done in " + monitor.getLastValue() + "ms.");
		
		return ranking.getBest();
	}
	
	private void filterTemplates(Collection<Template> templates){
		for (Iterator<Template> iterator = templates.iterator(); iterator.hasNext();) {
			Template template = iterator.next();
			try {
				QueryFactory.create(template.getQuery().toString(), Syntax.syntaxARQ);
			} catch (Exception e) {
				logger.debug("Invalid SPARQL:\n" + template.getQuery().toString(), e);
				iterator.remove();
			}
		}
	}
	
	public TemplateInstantiation answerQuestion(Template template, List<Double> parameters) {
		reset();
		
		//1. set the SPARQL query templates
		templates = Collections.singleton(template);
		
		//2. Entity URI Disambiguation
		logger.info("Running entity disambiguation...");
		monitor.start();
		SimpleEntityDisambiguation entityDisambiguation = new SimpleEntityDisambiguation(knowledgebase);
		Map<Template, Map<Slot, Collection<Entity>>> template2Allocations = entityDisambiguation.performEntityDisambiguation(templates);
		monitor.stop();
		logger.info("Done in " + monitor.getLastValue() + "ms.");
		
		//3. Generate possible instantiations of the templates, i.e. find entities for the slots
		logger.info("Running template instantiation...");
		monitor.start();
		Map<Template, List<TemplateInstantiation>> template2Instantiations = instantiateTemplates(template2Allocations);
		monitor.stop();
		logger.info("Done in " + monitor.getLastValue() + "ms.");
		
		//4. Rank the template instantiations
		logger.info("Running ranking...");
		monitor.start();
		RankingComputation rankingComputation = new SimpleRankingComputation(knowledgebase);
		Ranking ranking = rankingComputation.computeRanking(template2Instantiations, template2Allocations, parameters);
		monitor.stop();
		logger.info("Done in " + monitor.getLastValue() + "ms.");
		
		return ranking.getBest();
	}
	
	
	
	private List<TemplateInstantiation> instantiateTemplate(Template template, Map<Slot, Collection<Entity>> slot2Entities){
		List<TemplateInstantiation> instantiations = new ArrayList<TemplateInstantiation>();
		List<Map<Slot, Entity>> allocations = new ArrayList<Map<Slot,Entity>>();
		allocations.add(new HashMap<Slot, Entity>());
		
		for(Entry<Slot, Collection<Entity>> entry : slot2Entities.entrySet()){
			Slot slot = entry.getKey();
			Collection<Entity> candidateEntities = entry.getValue();
			if(!candidateEntities.isEmpty()){
				List<Map<Slot, Entity>> newAllocations = new ArrayList<Map<Slot,Entity>>();
				for (Entity entity : candidateEntities) {
					for (Map<Slot, Entity> allocation : allocations) {
						Map<Slot, Entity> newAllocation = new HashMap<Slot,Entity>();
						newAllocation.putAll(allocation);
						newAllocation.put(slot, entity);
						newAllocations.add(newAllocation);
					}
				}
				allocations.clear();
				allocations.addAll(newAllocations);
			}
		}
//		for(Entry<Slot, Collection<Entity>> entry : slot2Entities.entrySet()){
//			Slot slot = entry.getKey();
//			Collection<Entity> candidateEntities = entry.getValue();
//			List<Map<Slot, Entity>> newAllocations = new ArrayList<Map<Slot,Entity>>();
//			for(Entity entity : candidateEntities){
//				for(Map<Slot, Entity> allocation : allocations){
//					Map<Slot, Entity> newAllocation = new HashMap<Slot, Entity>(allocation);
//					newAllocation.put(slot, entity);
//					newAllocations.add(newAllocation);
//				}
//			}
////			allocations.clear();
//			allocations.addAll(newAllocations);
//		}
		for(Map<Slot, Entity> allocation : allocations){
			TemplateInstantiation templateInstantiation = new TemplateInstantiation(template, allocation);
			try {
				QueryFactory.create(templateInstantiation.getQuery(), Syntax.syntaxARQ);
				instantiations.add(templateInstantiation);
			} catch (Exception e) {
				logger.warn("Invalid SPARQL:\n" + templateInstantiation.getQuery(), e);
			}
		}
		return instantiations;
	}
	
	public Set<Template> getTemplates() {
		return templates;
	}
	
	private Map<Template, List<TemplateInstantiation>> instantiateTemplates(Map<Template, Map<Slot, Collection<Entity>>> template2Allocations){
		Map<Template, List<TemplateInstantiation>> template2Instantiations = new HashMap<Template, List<TemplateInstantiation>>();
		for (Entry<Template, Map<Slot,java.util.Collection<Entity>>> entry : template2Allocations.entrySet()) {
			Template template = entry.getKey();
			Map<Slot,java.util.Collection<Entity>> slot2Entities = entry.getValue();
			List<TemplateInstantiation> instantiations = instantiateTemplate(template, slot2Entities);
			template2Instantiations.put(template, instantiations);
		}
		return template2Instantiations;
	}
	
	private void printTopN(List<?> list, int n){
		for(int i = 0; i < Math.min(list.size(), n); i++){
			System.err.println(list.get(i).toString());
		}
	}
	
	
	public boolean executeAskQuery(String query){
		QueryEngineHTTP qe = new QueryEngineHTTP(endpoint.getURL().toString(), query);
		for(String uri : endpoint.getDefaultGraphURIs()){
			qe.addDefaultGraph(uri);
		}
		boolean ret = qe.execAsk();
		return ret;
	}
	
	public ResultSet executeSelect(String query) {
		ResultSet rs;
		if (model == null) {
			if (cache == null) {
				QueryEngineHTTP qe = new QueryEngineHTTP(endpoint.getURL().toString(), query);
				qe.setDefaultGraphURIs(endpoint.getDefaultGraphURIs());
				rs = qe.execSelect();
			} else {
				rs = SparqlQuery.convertJSONtoResultSet(cache.executeSelectQuery(endpoint, query));
			}
		} else {
			rs = QueryExecutionFactory.create(QueryFactory.create(query, Syntax.syntaxARQ), model)
					.execSelect();
		}
		
		return rs;
	}

}