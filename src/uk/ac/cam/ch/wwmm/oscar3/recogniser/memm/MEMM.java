package uk.ac.cam.ch.wwmm.oscar3.recogniser.memm;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import nu.xom.Attribute;
import nu.xom.Builder;
import nu.xom.Document;
import nu.xom.Element;
import nu.xom.Elements;
import nu.xom.Nodes;
import opennlp.maxent.DataIndexer;
import opennlp.maxent.Event;
import opennlp.maxent.EventCollectorAsStream;
import opennlp.maxent.GIS;
import opennlp.maxent.GISModel;
import opennlp.maxent.TwoPassDataIndexer;
import uk.ac.cam.ch.wwmm.oscar3.Oscar3Props;
import uk.ac.cam.ch.wwmm.oscar3.models.ExtractTrainingData;
import uk.ac.cam.ch.wwmm.oscar3.recogniser.document.HyphenTokeniser;
import uk.ac.cam.ch.wwmm.oscar3.recogniser.document.NamedEntity;
import uk.ac.cam.ch.wwmm.oscar3.recogniser.document.ProcessingDocument;
import uk.ac.cam.ch.wwmm.oscar3.recogniser.document.ProcessingDocumentFactory;
import uk.ac.cam.ch.wwmm.oscar3.recogniser.document.Token;
import uk.ac.cam.ch.wwmm.oscar3.recogniser.document.TokenSequence;
import uk.ac.cam.ch.wwmm.oscar3.recogniser.memm.rescorer.RescoreMEMMOut;
import uk.ac.cam.ch.wwmm.oscar3.recogniser.tokenanalysis.NGram;
import uk.ac.cam.ch.wwmm.ptclib.misc.SimpleEventCollector;
import uk.ac.cam.ch.wwmm.ptclib.misc.StringGISModelReader;
import uk.ac.cam.ch.wwmm.ptclib.misc.StringGISModelWriter;
import uk.ac.cam.ch.wwmm.ptclib.string.StringTools;
import uk.ac.cam.ch.wwmm.ptclib.xml.XOMTools;

/**The main class for generating and running MEMMs
 * 
 * @author ptc24
 *
 */
public final class MEMM {

	private Map<String, List<Event>> evsByPrev;
	private Map<String, Double> zeroProbs; 
	private Map<String, GISModel> gmByPrev;
	private GISModel ubermodel;
	private int trainingCycles;
	private int featureCutOff;
	
	Set<String> tagSet;
	Set<String> entityTypes;

	private boolean useUber = false;
	private boolean removeBlocked = false;
	private boolean retrain=true;
	private boolean splitTrain=true;
	//public boolean patternFeatures=false;
	private boolean featureSel=true;
	//public boolean tampering=false;
	private boolean simpleRescore=true;
	private boolean filtering=true;
	private boolean nameTypes=false;
		
	private Map<String,Map<String,Double>> featureCVScores;
	
	private Map<String,Set<String>> perniciousFeatures;
	
	private static double confidenceThreshold;
	
	private RescoreMEMMOut rescorer;
			
	MEMM() throws Exception {
		evsByPrev = new HashMap<String, List<Event>>();
		zeroProbs = new HashMap<String, Double>();
		gmByPrev = new HashMap<String, GISModel>();
		tagSet = new HashSet<String>();
		perniciousFeatures = null;
		
		trainingCycles = 100;
		featureCutOff = 1;
		confidenceThreshold = Oscar3Props.getInstance().neThreshold / 5.0;
		rescorer = null;
	}

	Set<String> getTagSet() {
		return tagSet;
	}
	
	Set<String> getEntityTypes() {
		return entityTypes;
	}

	private void train(List<String> features, String thisTag, String prevTag) {
		if(perniciousFeatures != null && perniciousFeatures.containsKey(prevTag) /*&& !tampering*/) {
			features.removeAll(perniciousFeatures.get(prevTag));
		}
		if(features.size() == 0) features.add("EMPTY");
		tagSet.add(thisTag);
		if(useUber) {
			features.add("$$prevTag=" + prevTag);
		}
		String [] c = features.toArray(new String[0]);
		Event ev = new Event(thisTag, c);
		List<Event> evs = evsByPrev.get(prevTag);
		if(evs == null) {
			evs = new ArrayList<Event>();
			evsByPrev.put(prevTag, evs);
		}
		evs.add(ev);
	}
	
	private void trainOnSentence(TokenSequence tokSeq, String domain) {
		FeatureExtractor extractor = new FeatureExtractor(tokSeq, domain);
		//extractor.printFeatures();
		List<Token> tokens = tokSeq.getTokens();
		String prevTag = "O";
		for(int i=0;i<tokens.size();i++) {
			train(extractor.getFeatures(i), tokens.get(i).getBioTag(), prevTag);
			prevTag = tokens.get(i).getBioTag();
		}
	}
	
	private void trainOnFile(File file, String domain) throws Exception {
		long time = System.currentTimeMillis();
		if(Oscar3Props.getInstance().verbose) System.out.print("Train on: " + file + "... ");
		Document doc = new Builder().build(file);
		Nodes n = doc.query("//cmlPile");
		for(int i=0;i<n.size();i++) n.get(i).detach();
		n = doc.query("//ne[@type='CPR']");
		for(int i=0;i<n.size();i++) XOMTools.removeElementPreservingText((Element)n.get(i));
		if(false) {
			n = doc.query("//ne[@type='CLASS']");
			for(int i=0;i<n.size();i++) XOMTools.removeElementPreservingText((Element)n.get(i));			
		}
		if(false) {
			n = doc.query("//ne");
			for(int i=0;i<n.size();i++) {
				Element e = (Element)n.get(i);
				e.addAttribute(new Attribute("type", "CHEMICAL"));
				//XOMTools.removeElementPreservingText((Element)n.get(i));
			}			
		}

		if(nameTypes) {
			n = doc.query("//ne");
			for(int i=0;i<n.size();i++) {
				Element ne = (Element)n.get(i);
				if(ne.getAttributeValue("type").equals("RN") && ne.getValue().matches("[A-Z]\\p{Ll}\\p{Ll}.*\\s.*")) {
					ne.addAttribute(new Attribute("type", "NRN"));
					if(Oscar3Props.getInstance().verbose) System.out.println("NRN: " + ne.getValue());
				} else if(ne.getAttributeValue("type").equals("CM") && ne.getValue().matches("[A-Z]\\p{Ll}\\p{Ll}.*\\s.*")) {
					ne.addAttribute(new Attribute("type", "NCM"));
					if(Oscar3Props.getInstance().verbose) System.out.println("NCM: " + ne.getValue());
				}
			}
		}
		
		ProcessingDocument procDoc = ProcessingDocumentFactory.getInstance().makeTokenisedDocument(doc, true, false, false);
		
	//NameRecogniser nr = new NameRecogniser();
		//nr.halfProcess(doc);
		//if(patternFeatures) {
		//	nr.findForReps(true);
		//} else {
			//nr.makeTokenisers(true);
		//}
		for(TokenSequence ts : procDoc.getTokenSequences()) {
			trainOnSentence(ts, domain);
		}
		if(Oscar3Props.getInstance().verbose) System.out.println(System.currentTimeMillis() - time);
	}
	
	void trainOnSbFilesNosplit(List<File> files, Map<File,String> domains) throws Exception {
		if(retrain) {
			ExtractTrainingData.clear();
			HyphenTokeniser.reinitialise();
			ExtractTrainingData.reinitialise(files);
			NGram.reinitialise();
			HyphenTokeniser.reinitialise();					
		}
		for(File f : files) {
			String domain = null;
			if(domains != null && domains.containsKey(f)) domain = domains.get(f);
			trainOnFile(f, domain);
		}				
		finishTraining();
	}
	
	void trainOnSbFiles(List<File> files, Map<File,String> domains) throws Exception {
		if(!splitTrain) {
			trainOnSbFilesNosplit(files, domains);
			return;
		}
		List<Set<File>> splitTrainFiles = new ArrayList<Set<File>>();
		List<Set<File>> splitTrainAntiFiles = new ArrayList<Set<File>>();
		int splitNo = 2;
		
		for(int i=0;i<splitNo;i++) {
			splitTrainFiles.add(new HashSet<File>());
			splitTrainAntiFiles.add(new HashSet<File>());
		}
		
		for(int i=0;i<files.size();i++) {
			for(int j=0;j<splitNo;j++) {
				if(j == i % splitNo) {
					splitTrainFiles.get(j).add(files.get(i));
				} else {
					splitTrainAntiFiles.get(j).add(files.get(i));
				}
			}
		}
		
		for(int split=0;split<splitNo;split++) {
			if(retrain) {
				ExtractTrainingData.clear();
				HyphenTokeniser.reinitialise();
				ExtractTrainingData.reinitialise(splitTrainAntiFiles.get(split));
				NGram.reinitialise();
				HyphenTokeniser.reinitialise();					
			}
			
			int fileno = 0;
			for(File f : splitTrainFiles.get(split)) {
				fileno++;			
				String domain = null;
				if(domains != null && domains.containsKey(f)) domain = domains.get(f);
				trainOnFile(f, domain);
			}				
		}

		finishTraining();
		if(retrain) {
			ExtractTrainingData.clear();
			HyphenTokeniser.reinitialise();
			ExtractTrainingData.reinitialise(files);;
			NGram.reinitialise();
			HyphenTokeniser.reinitialise();				
		}
	}

	void trainOnSbFilesWithCVFS(List<File> files, Map<File,String> domains) throws Exception {
		List<List<File>> splitTrainFiles = new ArrayList<List<File>>();
		List<List<File>> splitTrainAntiFiles = new ArrayList<List<File>>();
		int splitNo = 3;
		
		for(int i=0;i<splitNo;i++) {
			splitTrainFiles.add(new ArrayList<File>());
			splitTrainAntiFiles.add(new ArrayList<File>());
		}
		
		for(int i=0;i<files.size();i++) {
			for(int j=0;j<splitNo;j++) {
				if(j == i % splitNo) {
					splitTrainFiles.get(j).add(files.get(i));
				} else {
					splitTrainAntiFiles.get(j).add(files.get(i));
				}
			}
		}
		
		for(int split=0;split<splitNo;split++) {
			trainOnSbFiles(splitTrainAntiFiles.get(split), domains);
			evsByPrev.clear();
			for(File f : splitTrainFiles.get(split)) {
				String domain = null;
				if(domains != null && domains.containsKey(f)) domain = domains.get(f);
				cvFeatures(f, domain);
			}				
		}
		
		findPerniciousFeatures();
		trainOnSbFiles(files, domains);
		/*if(tampering) {
			List<String> prefixesToRemove = new ArrayList<String>();
			prefixesToRemove.add("anchor=");
			for(String tag : gmByPrev.keySet()) {
				gmByPrev.put(tag, Tamperer.tamperModel(gmByPrev.get(tag), perniciousFeatures.get(tag), prefixesToRemove));
			}			
		}*/
	}

	void trainOnSbFilesWithRescore(List<File> files, Map<File,String> domains) throws Exception {
		rescorer = new RescoreMEMMOut();
		List<List<File>> splitTrainFiles = new ArrayList<List<File>>();
		List<List<File>> splitTrainAntiFiles = new ArrayList<List<File>>();
		int splitNo = 3;
		
		for(int i=0;i<splitNo;i++) {
			splitTrainFiles.add(new ArrayList<File>());
			splitTrainAntiFiles.add(new ArrayList<File>());
		}
		
		for(int i=0;i<files.size();i++) {
			for(int j=0;j<splitNo;j++) {
				if(j == i % splitNo) {
					splitTrainFiles.get(j).add(files.get(i));
				} else {
					splitTrainAntiFiles.get(j).add(files.get(i));
				}
			}
		}
		
		for(int split=0;split<splitNo;split++) {
			if(simpleRescore) {
				trainOnSbFiles(splitTrainAntiFiles.get(split), domains);
			} else {
				trainOnSbFilesWithCVFS(splitTrainAntiFiles.get(split), domains);
			}
			for(File f : splitTrainFiles.get(split)) {
				String domain = null;
				if(domains != null && domains.containsKey(f)) domain = domains.get(f);
				rescorer.trainOnFile(f, domain, this);
			}				
			evsByPrev.clear();
			if(!simpleRescore) {
				featureCVScores.clear();
				perniciousFeatures.clear();
			}
		}
		rescorer.finishTraining();
		if(simpleRescore) {
			trainOnSbFiles(files, domains);
		} else {
			trainOnSbFilesWithCVFS(files, domains);
		}
	}

	
	
	
	private void makeEntityTypesAndZeroProbs() {
		entityTypes = new HashSet<String>();
		for(String tagType : tagSet) {
			if(tagType.startsWith("B-") || tagType.startsWith("W-")) entityTypes.add(tagType.substring(2));
		}

				
		for(String tag : tagSet) {
			zeroProbs.put(tag, 0.0);
		}			
	}
	
	private void finishTraining() throws Exception {
		makeEntityTypesAndZeroProbs();
		
		if(useUber) {
			List<Event> evs = new ArrayList<Event>();
			for(String prevTagg : evsByPrev.keySet()) {
				evs.addAll(evsByPrev.get(prevTagg));
			}
			DataIndexer di = new TwoPassDataIndexer(new EventCollectorAsStream(new SimpleEventCollector(evs)), featureCutOff);
			ubermodel = GIS.trainModel(trainingCycles, di);
		} else {
			for(String prevTagg : evsByPrev.keySet()) {
				if(Oscar3Props.getInstance().verbose) System.out.println(prevTagg);
				List<Event> evs = evsByPrev.get(prevTagg);
				if(featureSel) {
					evs = new FeatureSelector().selectFeatures(evs);						
				}		
				if(evs.size() == 1) {
					evs.add(evs.get(0));
				}
				DataIndexer di = null;
				try {
					di = new TwoPassDataIndexer(new EventCollectorAsStream(new SimpleEventCollector(evs)), featureCutOff);
					gmByPrev.put(prevTagg, GIS.trainModel(trainingCycles, di));
				} catch (Exception e) {
					di = new TwoPassDataIndexer(new EventCollectorAsStream(new SimpleEventCollector(evs)), 1);				
					gmByPrev.put(prevTagg, GIS.trainModel(trainingCycles, di));
				}	
			}
		}
	}
	
	private Map<String, Double> runGIS(GISModel gm, String [] context) {
		Map<String, Double> results = new HashMap<String, Double>();
		results.putAll(zeroProbs);
		double [] gisResults = gm.eval(context);
		for(int i=0;i<gisResults.length;i++) {
			results.put(gm.getOutcome(i), gisResults[i]);
		}
		return results;
	}
	
	private Map<String,Map<String,Double>> calcResults(List<String> features) {
		Map<String,Map<String,Double>> results = new HashMap<String,Map<String,Double>>();
		if(useUber) {
			for(String prevTag : tagSet) {
				List<String> newFeatures = new ArrayList<String>(features);
				newFeatures.add("$$prevTag=" + prevTag);
				results.put(prevTag, runGIS(ubermodel, newFeatures.toArray(new String[0])));					
			}
		} else {
			String [] featArray = features.toArray(new String[0]);
			for(String tag : tagSet) {
				if(false /* && tampering*/) {
					List<String> newFeatures = new ArrayList<String>(features.size());
					for(String feature : features) {
						if(perniciousFeatures != null && perniciousFeatures.containsKey(tag) && perniciousFeatures.get(tag).contains(feature)) {
							//System.out.println("Dropping: " + feature);
						} else {
							if(!feature.startsWith("anchor")) newFeatures.add(feature);							
						}
					}
					featArray = newFeatures.toArray(new String[0]);
				}
				GISModel gm = gmByPrev.get(tag);
				if(gm == null) continue;
				Map<String, Double> modelResults = runGIS(gm, featArray);
				results.put(tag, modelResults);
			}
		}
		return results;
	}
	
	/**Finds the named entities in a token sequence.
	 * 
	 * @param tokSeq The token sequence.
	 * @param domain A string to represent the domain (experimental, should
	 * usually be null).
	 * @return Named entities, with confidences.
	 */
	public Map<NamedEntity,Double> findNEs(TokenSequence tokSeq, String domain) {
		FeatureExtractor extractor = new FeatureExtractor(tokSeq, domain);
		List<Token> tokens = tokSeq.getTokens();
		if(tokens.size() == 0) return new HashMap<NamedEntity,Double>();

		List<Map<String,Map<String,Double>>> classifierResults = new ArrayList<Map<String,Map<String,Double>>>();	
		for(int i=0;i<tokens.size();i++) {
			classifierResults.add(calcResults(extractor.getFeatures(i))); 
		}
		
		Lattice lattice = new Lattice(this, tokSeq, classifierResults);
		Map<NamedEntity,Double> neConfidences = lattice.getEntities(confidenceThreshold);
		PostProcessor pp = new PostProcessor(tokSeq, neConfidences);
		if(filtering) pp.filterEntities();
		pp.getBlocked();
		if(removeBlocked) pp.removeBlocked();
		neConfidences = pp.getEntities();
		
		return neConfidences;
	}
	
	private void cvFeatures(File file, String domain) throws Exception {
		long time = System.currentTimeMillis();
		if(Oscar3Props.getInstance().verbose) System.out.print("Cross-Validate features on: " + file + "... ");
		Document doc = new Builder().build(file);
		Nodes n = doc.query("//cmlPile");
		for(int i=0;i<n.size();i++) n.get(i).detach();
		n = doc.query("//ne[@type='CPR']");
		for(int i=0;i<n.size();i++) XOMTools.removeElementPreservingText((Element)n.get(i));
		
		
		ProcessingDocument procDoc = ProcessingDocumentFactory.getInstance().makeTokenisedDocument(doc, true, false, false);
		//NameRecogniser nr = new NameRecogniser();
		//nr.halfProcess(doc);
		//if(patternFeatures) {
		//	nr.findForReps(true);
		//} else {
			//nr.makeTokenisers(true);
		//}
		for(TokenSequence ts : procDoc.getTokenSequences()) {
			cvFeatures(ts, domain);
		}
		if(Oscar3Props.getInstance().verbose) System.out.println(System.currentTimeMillis() - time);
	}

	
	private double infoLoss(double [] probs, int index) {
		return -Math.log(probs[index])/Math.log(2);
	}
	
	private void cvFeatures(TokenSequence tokSeq, String domain) {
		if(featureCVScores == null) {
			featureCVScores = new HashMap<String,Map<String,Double>>();
		}
		FeatureExtractor extractor = new FeatureExtractor(tokSeq, domain);
		List<Token> tokens = tokSeq.getTokens();
		String prevTag = "O";
		for(int i=0;i<tokens.size();i++) {
			String tag = tokens.get(i).getBioTag();
			GISModel gm = gmByPrev.get(prevTag);
			if(gm == null) continue;
			Map<String,Double> scoresForPrev = featureCVScores.get(prevTag);
			if(scoresForPrev == null) {
				scoresForPrev = new HashMap<String,Double>();
				featureCVScores.put(prevTag, scoresForPrev);
			}
			
			prevTag = tag;
			int outcomeIndex = gm.getIndex(tag);
			if(outcomeIndex == -1) continue;
			List<String> features = extractor.getFeatures(i);
			if(features.size() == 0) continue;
			String [] featuresArray = features.toArray(new String[0]);
			String [] newFeaturesArray = features.toArray(new String[0]);
			double [] baseProbs = gm.eval(featuresArray);
			for(int j=0;j<features.size();j++) {
				newFeaturesArray[j] = "IGNORETHIS";
				double [] newProbs = gm.eval(newFeaturesArray);
				double gain = infoLoss(newProbs, outcomeIndex) - infoLoss(baseProbs, outcomeIndex);
				if(Double.isNaN(gain)) gain = 0.0;
				String feature = features.get(j);
				double oldScore = 0.0;
				if(scoresForPrev.containsKey(feature)) oldScore = scoresForPrev.get(feature);
				scoresForPrev.put(feature, gain + oldScore);
				newFeaturesArray[j] = featuresArray[j];
			}
		}
	}
	
	private void findPerniciousFeatures() {
		perniciousFeatures = new HashMap<String,Set<String>>();
		for(String prev : featureCVScores.keySet()) {
			Set<String> pffp = new HashSet<String>();
			perniciousFeatures.put(prev, pffp);
			
			List<String> features = StringTools.getSortedList(featureCVScores.get(prev));
			for(String feature : features) {
				double score = featureCVScores.get(prev).get(feature);
				if(score < 0.0) {
					if(Oscar3Props.getInstance().verbose) System.out.println("Removing:\t" + prev + "\t" + feature + "\t" + score);
					pffp.add(feature);
				}
			}
		}
	}
	
	/**Produces an XML element containing the current MEMM model.
	 * 
	 * @return The XML element.
	 * @throws Exception
	 */
	public Element writeModel() throws Exception {
		Element root = new Element("memm");
		for(String prev : gmByPrev.keySet()) {
			Element maxent = new Element("maxent");
			maxent.addAttribute(new Attribute("prev", prev));
			StringGISModelWriter sgmw = new StringGISModelWriter(gmByPrev.get(prev));
			sgmw.persist();
			maxent.appendChild(sgmw.toString());
			root.appendChild(maxent);
		}
		if(rescorer != null) {
			root.appendChild(rescorer.writeElement());
		}
		return root;
	}

	/**Reads in an XML document containing a MEMM model.
	 * 
	 * @param doc The XML document.
	 * @throws Exception
	 */
	public void readModel(Document doc) throws Exception {
		readModel(doc.getRootElement());
	}

	/**Reads in a MEMM model from an XML element.
	 * 
	 * @param memmRoot The XML element.
	 * @throws Exception
	 */
	public void readModel(Element memmRoot) throws Exception {
		Elements maxents = memmRoot.getChildElements("maxent");
		gmByPrev = new HashMap<String,GISModel>();
		tagSet = new HashSet<String>();
		for(int i=0;i<maxents.size();i++) {
			Element maxent = maxents.get(i);
			String prev = maxent.getAttributeValue("prev");
			StringGISModelReader sgmr = new StringGISModelReader(maxent.getValue());
			GISModel gm = sgmr.getModel();
			gmByPrev.put(prev, gm);
			tagSet.add(prev);
			for(int j=0;j<gm.getNumOutcomes();j++) {
				tagSet.add(gm.getOutcome(j));
			}
		}
		Element rescorerElem = memmRoot.getFirstChildElement("rescorer");
		if(rescorerElem != null) {
			rescorer = new RescoreMEMMOut();
			rescorer.readElement(rescorerElem);
		} else {
			rescorer = null;
		}
		makeEntityTypesAndZeroProbs();
	}
	
	/**Uses this MEMM's rescorer to rescore a list of named entities. This
	 * updates the confidence values held within the NEs.
	 * 
	 * @param entities The entities to rescore.
	 */
	public void rescore(List<NamedEntity> entities) {
		if(rescorer != null) rescorer.rescore(entities);
	}
}
