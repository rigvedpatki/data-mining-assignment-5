package se.kth.jabeja;

import org.apache.log4j.Logger;
import se.kth.jabeja.config.Config;
import se.kth.jabeja.config.NodeSelectionPolicy;
import se.kth.jabeja.io.FileIO;
import se.kth.jabeja.rand.RandNoGenerator;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class Jabeja {
  final static Logger logger = Logger.getLogger(Jabeja.class);
  private final Config config;
  private final HashMap<Integer/* id */, Node/* neighbors */> entireGraph;
  private final List<Integer> nodeIds;
  private int numberOfSwaps;
  private int round;
  private float T;
  private float Tmin;
  private boolean resultFileCreated = false;

  // -------------------------------------------------------------------
  public Jabeja(HashMap<Integer, Node> graph, Config config) {
    this.entireGraph = graph;
    this.nodeIds = new ArrayList<Integer>(entireGraph.keySet());
    this.round = 0;
    this.numberOfSwaps = 0;
    this.config = config;
    this.T = config.getTemperature();
    this.Tmin = config.getTemperatureMin();
  }

  // -------------------------------------------------------------------
  public void startJabeja() throws IOException {
    for (round = 0; round < config.getRounds(); round++) {
      for (int id : entireGraph.keySet()) {
        sampleAndSwap(id);
      }

      // one cycle for all nodes have completed.
      // reduce the temperature
      saCoolDown(config.getCoolingMode());
      report();
    }
  }

  /**
   * Simulated anneal cooling function
   */
  private void saCoolDown(int mode) {

    switch (mode) {
    case 1:
      T *= config.getDelta();
      break;

    case 2:
      T *= Math.pow(config.getDelta(), round / 100);
      break;

    case 3:
      T = T / (1 + config.getDelta() * round);
      break;

    default:
      throw new IllegalArgumentException("Cooldown mode not valid.");
    }

  }

  /**
   * Sample and swap algorithm at node p
   * 
   * @param nodeId
   */
  private void sampleAndSwap(int nodeId) {
    Node partner = null;
    Node nodeP = entireGraph.get(nodeId);

    if (config.getNodeSelectionPolicy() == NodeSelectionPolicy.HYBRID
        || config.getNodeSelectionPolicy() == NodeSelectionPolicy.LOCAL) {
      // swap with a neighbor selected from neighbors random sample
      partner = findPartner(nodeId, getNeighbors(nodeP));

    }

    if (config.getNodeSelectionPolicy() == NodeSelectionPolicy.HYBRID
        || config.getNodeSelectionPolicy() == NodeSelectionPolicy.RANDOM) {
      // if local policy fails then randomly sample the entire graph
      if (partner == null) {
        partner = findPartner(nodeId, getSample(nodeId));
      }
    }

    // swap the colors (only if a partner has been found)
    if (partner != null) {
      int swap = nodeP.getColor();
      nodeP.setColor(partner.getColor());
      partner.setColor(swap);
      // Take the initial color as the host of the node to compute swaps
      if (nodeP.getInitColor() != partner.getInitColor()) {
        this.numberOfSwaps++;
      }
    }
  }

  /**
   * Get the best partner to exchange with amongst a list of candidates (ids).
   * 
   * @param nodePId           The id of the node looking for a partner.
   * @param neighbouringNodes The ids of the candidate nodes.
   * @return bestPartner The best partner found; null if none found.
   */

  public Node findPartner(int nodePId, Integer[] neighbouringNodes) {

    // get node P from the nodePId
    Node nodeP = entireGraph.get(nodePId);
    // initialize the highestBenefit as 0
    double highestBenefit = 0;
    // initialize best possible neighbour node for node p
    Node bestPartner = null;

    // alpha
    double alpha = config.getAlpha();

    // running through the neighbouring nodes
    for (Integer nodeQId : neighbouringNodes) {
      // getting the node Q from nodeQId
      Node nodeQ = entireGraph.get(nodeQId);

      // getting degree for node P with color of node P
      int degreePColorP = getDegree(nodeP, nodeP.getColor());
      // getting degree for node Q with color of node Q
      int degreeQColorQ = getDegree(nodeQ, nodeQ.getColor());
      // calculating old
      double old_ = Math.pow(degreePColorP, alpha) + Math.pow(degreeQColorQ, alpha);
      // getting degree for node P with color of node Q
      int degreePColorQ = getDegree(nodeP, nodeQ.getColor());
      // getting degree for node Q with color of node P
      int degreeQColorP = getDegree(nodeQ, nodeP.getColor());
      // calculating new
      double new_ = Math.pow(degreePColorQ, alpha) + Math.pow(degreeQColorP, alpha);

      // checking which is better new or old
      // updating the values for best partner and highestBenefit
      /*
       * if ((new_ * T > old_) && (new_ > highestBenefit)) { bestPartner = nodeQ;
       * highestBenefit = new_; }
       */

      // Instead of cost use benefit as the difference between
      // new and old state
      double newBenefit = new_ - old_;

      // Apply acceptance probability to simulated annealing
      // based on benefit instead of cost (change sign: new - old)
      double ap = 0;
      if (newBenefit > highestBenefit) {
        ap = 1;
      } else {
        switch (config.getAcceptanceProbabilityMode()) {
        case 1:
          ap = Math.pow(Math.E, (newBenefit - highestBenefit) / T);
          break;

        case 2:
          ap = 1 / (1 + Math.pow(Math.E, (highestBenefit - newBenefit) / T));
          break;

        default:
          throw new IllegalArgumentException("The selected mode for ap is not valid.");
        }
      }
      if ((ap > RandNoGenerator.random()) && (T > Tmin || newBenefit > highestBenefit)) {
        bestPartner = nodeQ;
        highestBenefit = newBenefit;
      }

    }
    return bestPartner;
  }

  /**
   * The degreee of the node based on color
   * 
   * @param node
   * @param colorId
   * @return how many neighbors of the node have color == colorId
   */
  private int getDegree(Node node, int colorId) {
    int degree = 0;
    for (int neighborId : node.getNeighbours()) {
      Node neighbor = entireGraph.get(neighborId);
      if (neighbor.getColor() == colorId) {
        degree++;
      }
    }
    return degree;
  }

  /**
   * Returns a uniformly random sample of the graph
   * 
   * @param currentNodeId
   * @return Returns a uniformly random sample of the graph
   */
  private Integer[] getSample(int currentNodeId) {
    int count = config.getUniformRandomSampleSize();
    int rndId;
    int size = entireGraph.size();
    ArrayList<Integer> rndIds = new ArrayList<Integer>();

    while (true) {
      rndId = nodeIds.get(RandNoGenerator.nextInt(size));
      if (rndId != currentNodeId && !rndIds.contains(rndId)) {
        rndIds.add(rndId);
        count--;
      }

      if (count == 0)
        break;
    }

    Integer[] ids = new Integer[rndIds.size()];
    return rndIds.toArray(ids);
  }

  /**
   * Get random neighbors. The number of random neighbors is controlled using
   * -closeByNeighbors command line argument which can be obtained from the config
   * using {@link Config#getRandomNeighborSampleSize()}
   * 
   * @param node
   * @return
   */
  private Integer[] getNeighbors(Node node) {
    ArrayList<Integer> list = node.getNeighbours();
    int count = config.getRandomNeighborSampleSize();
    int rndId;
    int index;
    int size = list.size();
    ArrayList<Integer> rndIds = new ArrayList<Integer>();

    if (size <= count)
      rndIds.addAll(list);
    else {
      while (true) {
        index = RandNoGenerator.nextInt(size);
        rndId = list.get(index);
        if (!rndIds.contains(rndId)) {
          rndIds.add(rndId);
          count--;
        }

        if (count == 0)
          break;
      }
    }

    Integer[] arr = new Integer[rndIds.size()];
    return rndIds.toArray(arr);
  }

  /**
   * Generate a report which is stored in a file in the output dir.
   *
   * @throws IOException
   */
  private void report() throws IOException {
    int grayLinks = 0;
    int migrations = 0; // number of nodes that have changed the initial color

    for (int i : entireGraph.keySet()) {
      Node node = entireGraph.get(i);
      int nodeColor = node.getColor();
      ArrayList<Integer> nodeNeighbours = node.getNeighbours();

      if (nodeColor != node.getInitColor()) {
        migrations++;
      }

      if (nodeNeighbours != null) {
        for (int n : nodeNeighbours) {
          Node p = entireGraph.get(n);
          int pColor = p.getColor();

          if (nodeColor != pColor)
            grayLinks++;
        }
      }
    }

    int edgeCut = grayLinks / 2;

    logger.info("round: " + round + ", edge cut:" + edgeCut + ", swaps: " + numberOfSwaps + ", migrations: "+ migrations + ", T: " + T);

    saveToFile(edgeCut, migrations);
  }

  private void saveToFile(int edgeCuts, int migrations) throws IOException {
    String delimiter = "\t\t";
    String outputFilePath;

    // output file name
    File inputFile = new File(config.getGraphFilePath());
    outputFilePath = config.getOutputDir() + File.separator + inputFile.getName() + "_" + "NS" + "_"
        + config.getNodeSelectionPolicy() + "_" + "GICP" + "_" + config.getGraphInitialColorPolicy() + "_" + "T" + "_"+ config.getTemperature() + "_" + "D" + "_" + config.getDelta() + "_" + "RNSS" + "_"+ config.getRandomNeighborSampleSize() + "_" + "URSS" + "_" + config.getUniformRandomSampleSize() + "_" + "A" + "_" + config.getAlpha() + "_" + "R" + "_" + config.getRounds() + "_" + "COOL" + "_" + config.getCoolingMode()+ "_" + "AP" + "_" + config.getAcceptanceProbabilityMode() + ".txt";

    if (!resultFileCreated) {
      File outputDir = new File(config.getOutputDir());
      if (!outputDir.exists()) {
        if (!outputDir.mkdir()) {
          throw new IOException("Unable to create the output directory");
        }
      }
      // create folder and result file with header
      String header = "# Migration is number of nodes that have changed color.";
      header += "\n\nRound" + delimiter + 
                "Edge-Cut" + delimiter + 
                "Swaps" + delimiter + 
                "Migrations" + delimiter + 
                "T" + delimiter + 
                "Skipped" + "\n";
      FileIO.write(header, outputFilePath);
      resultFileCreated = true;
    }

    FileIO.append(round + delimiter +
                  edgeCuts + delimiter + 
                  numberOfSwaps + delimiter + 
                  migrations + delimiter + 
                  T + "\n", outputFilePath);
  }
}