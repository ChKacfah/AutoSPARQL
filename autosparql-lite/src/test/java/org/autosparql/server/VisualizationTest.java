package org.autosparql.server;

import java.util.SortedSet;
import java.util.TreeSet;
import org.aksw.autosparql.server.AutoSPARQLSession;
import org.aksw.autosparql.server.Visualization;
import org.aksw.autosparql.shared.Example;
import org.junit.Test;

public class VisualizationTest
{
	@Test
	public void testVisualize()
	{
		String[] exampleURIs =
			{
				"http://dbpedia.org/resource/Angels_&_Demons",
				"http://dbpedia.org/resource/Deception_Point",
				"http://dbpedia.org/resource/Digital_Fortress",
				"http://dbpedia.org/resource/The_Da_Vinci_Code",
				"http://dbpedia.org/resource/The_Lost_Symbol"
			};
		final AutoSPARQLSession session = new AutoSPARQLSession();
		SortedSet<Example> examples = new TreeSet<Example>();
		for(String uri:exampleURIs)
		{
			examples.add(new Example(uri));
		}
		session.fillExamples(examples);
		String sparqlQuery = "SELECT ?y WHERE {?y rdf:type <http://dbpedia.org/ontology/Book> .?y ?p0 <http://dbpedia.org/resource/Dan_Brown>.}";
		String sparqlEndpoint = "http://live.dbpedia.org/sparql";
		Visualization.visualize(examples, sparqlQuery, sparqlEndpoint);
	}
}