/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package architecture;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import mikera.vectorz.AVector;
import mikera.vectorz.Vector;
import java.util.LinkedList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import mikera.matrixx.AMatrix;
import mikera.matrixx.Matrix;
import mikera.vectorz.Vectorz;
import nickd4j.ReadWriteUtilities;

/**
 * Class FragmentedNeuralQueue is an implementation of a fragmented neural queue
 * simplified for operation (cannot be used for training) of a
 * CompressingAutoencoder A fragmented neural queue is similar to a neural queue
 * as proposed by Google DeepMind, but separates enqueueing and dequeueing
 * operations by means of vector combination in the scalar space of vectors'
 * assigned strengths. Wow. Thats the fancy definition. Really what this means
 * is that unlike a neural queue, which pushes and pops portions of vectors over
 * the entire queue, this fragmented queue design, on dequeue, combines vectors
 * from the front of the queue until the total strength is 1.0, returns that combination,
 * and then pops off only the first vector. It makes for a very fun interpolation between
 * recognized features, and allows for decoding to start before an entire
 * dataset is encoded.
 *
 * @author Nicholas Weintraut
 */
public class FragmentedNeuralQueue {

    private LinkedList<AVector> vectorList; //[index][vectorIndex]
    private LinkedList<Double> strengthList; //[index]
    private double fragmentStrength;
    private Double totalStrength;

    /**
     * Initializes FragmentedNeuralQueue instance with the given input vector
     * size and a fragment strength of 1.
     */
    public FragmentedNeuralQueue() {
        this(1);
    }

    /**
     * Initializes FragmentedNeuralQueue instance with the given input vector
     * size and fragment strength
     *
     * @param fragmentStrength
     */
    public FragmentedNeuralQueue(double fragmentStrength) {
        vectorList = new LinkedList<AVector>();
        strengthList = new LinkedList<Double>();
        this.fragmentStrength = fragmentStrength;
        totalStrength = 0.0;
    }

    public boolean isEmpty() {
        return vectorList.isEmpty();
    }

    public void resetFeatures() {
        for (AVector vector : vectorList) {
            vector.sub(vector);
        }
    }

    public void crossover(FragmentedNeuralQueue otherQueue, int numSwaps) {
        Random rand = new Random();
        List<Integer> featureIndexes = getFeatureIndexes();
        for (int i = 0; i < numSwaps; i++) {
            int selectedFeatureIndex = rand.nextInt(featureIndexes.size());
            AVector tempVector = vectorList.get(selectedFeatureIndex);
            vectorList.set(selectedFeatureIndex, otherQueue.vectorList.get(selectedFeatureIndex));
            otherQueue.vectorList.set(selectedFeatureIndex, tempVector);
        }
    }

    public void addNoise(double magnitude) {
        for (AVector vector : vectorList) {
            AVector noiseVector = Vector.createLength(vector.length());
            Random rand = new Random();
            Vectorz.fillNormal(noiseVector, rand);
            noiseVector.multiply(magnitude);
            vector.add(noiseVector);
        }
    }

    public void weightedAverageFeatures(List<FragmentedNeuralQueue> queues, AVector weights) {
        resetFeatures();
        for (int i = 0; i < queues.size(); i++) {
            for (int j = 0; j < vectorList.size(); j++) {
                vectorList.get(j).add(queues.get(i).vectorList.get(j).multiplyCopy(weights.get(i)));
            }
        }
    }

    /**
     * Perform the next timeStep iteration of the neural queue without
     * dequeueing
     * @param inputVector
     * @param enqueueSignal
     *
     */
    public void enqueueStep(AVector inputVector, double enqueueSignal) {
        vectorList.add(inputVector);
        strengthList.add(enqueueSignal);
        totalStrength += enqueueSignal;
    }

    public void shuffleVectors() {
        Collections.shuffle(vectorList);
    }

    public void halfAndHalfQueue() {
        for (int i = 0; i < strengthList.size() / 2; i++) {
            strengthList.offer(strengthList.poll());
            vectorList.offer(vectorList.poll());
        }
    }

    public void shuffleQueue() {
        Random rand = new Random();
        List<Double> strengths = (List<Double>) strengthList;
        List<AVector> vectors = (List<AVector>) vectorList;
        for (int i = 0; i < strengths.size(); i++) {
            int sourceIndex = rand.nextInt(strengths.size());
            int destIndex = rand.nextInt(strengths.size());
            AVector tempVector = vectors.get(destIndex);
            Double tempStrength = strengths.get(destIndex);
            strengths.set(destIndex, strengths.get(sourceIndex));
            vectors.set(destIndex, vectors.get(sourceIndex));
            strengths.set(sourceIndex, tempStrength);
            vectors.set(sourceIndex, tempVector);
        }
    }

    /**
     * Based on vector strengths, returns lists of contiguous vectors that
     * represent musical "features" as recognized by the encoder.
     *
     * @return List of lists of indexes that point to paired vectors and
     * strengths in the queue
     */
    public List<List<Integer>> findFeatureGroups() {
        double threshold = 0.6;
        List<List<Integer>> featureGroups = new ArrayList<>();
        featureGroups.add(new ArrayList<>());
        Iterator<Double> strengthIterator = strengthList.iterator();
        boolean filledGroup = false;
        int strengthIndex = 0;
        while (strengthIterator.hasNext()) {
            Double strength = strengthIterator.next();
            if (filledGroup && strength < threshold) {
                featureGroups.add(new ArrayList<>());
                filledGroup = false;
            }
            featureGroups.get(featureGroups.size() - 1).add(strengthIndex);
            if (strength >= threshold) {
                filledGroup = true;
            }
            strengthIndex++;
        }
        System.out.println(featureGroups.size() + " feature groups found");
        System.out.println((strengthList.size() / featureGroups.size()) + " timeSteps per feature group(rounded down)");
        return featureGroups;
    }

    public AMatrix getFeatureMatrix() {
        List<Integer> featureIndexes = getFeatureIndexes();
        AVector[] features = new AVector[featureIndexes.size()];

        for (int i = 0; i < featureIndexes.size(); i++) {
            features[i] = vectorList.get(featureIndexes.get(i));
        }
        return Matrix.create(features);
    }

    public void initFromFeatureMatrix(AMatrix featureMatrix, int spacing) {
        strengthList = new LinkedList<>();
        vectorList = new LinkedList<>();
        int numFeatures = featureMatrix.rowCount();
        int featureSize = featureMatrix.columnCount();
        int currFeature = 0;
        for (int i = 0; i < spacing * numFeatures; i++) {
            AVector newVector = (((i + 1) % spacing) == 0) ? featureMatrix.getRow(currFeature++) : Vector.createLength(featureSize);
            vectorList.add(newVector);
            strengthList.add((((i + 1) % spacing) == 0) ? 1.0 : 0.0);
        }
    }

    public void printFeatureGroups() {
        for (List<Integer> group : findFeatureGroups()) {
            double maxStrength = 0;
            int maxIndex = 0;
            for (int i = 0; i < group.size(); i++) {
                if (strengthList.get(group.get(i)) > maxStrength) {
                    maxStrength = strengthList.get(group.get(i));
                    maxIndex = group.get(i);
                }
            }
            System.out.println("" + vectorList.get(maxIndex) + " with strength " + maxStrength + " at timeStep " + maxIndex);
        }
    }

    /**
     *
     * @return Does the queue have
     */
    public boolean hasFullBuffer() {
        return totalStrength >= fragmentStrength;
    }

    public String toString() {
        return strengthList.size() + " " + vectorList.size();
    }

    /**
     * Read vectors scaled by their strengths until we fill the fragment, then
     * return the fragment. A vector is scaled by a portion of their strength if
     * their strength would overfill the fragment.
     *
     * @return The sampled vector
     */
    public AVector peek() {
        if (!vectorList.isEmpty()) {
            //lets start generating the readVector
            AVector readVector = Vector.createLength(vectorList.peek().length());

            double strengthSum = 0.0;

            Iterator<AVector> vectorIterator = vectorList.iterator();
            Iterator<Double> strengthIterator = strengthList.iterator();
            //while we have not reached the strength limit for our fragment
            while (strengthSum < fragmentStrength && vectorIterator.hasNext()) {
                AVector currVector = vectorIterator.next();
                //System.out.println(currVector.length());
                double currStrength = strengthIterator.next();
                //if our strengthSum would exceed our fragment strength, only take the portion needed to fill it
                if (strengthSum + currStrength > fragmentStrength) {
                    AVector currVectorCopy = currVector.copy();
                    currVectorCopy.multiply(fragmentStrength - strengthSum);
                    readVector.add(currVectorCopy);
                    strengthSum = fragmentStrength;
                } //if we have space left, we'll simply multiply the current vector by it's scale and add it.
                else {
                    AVector currVectorCopy = currVector.copy();
                    currVectorCopy.multiply(currStrength);
                    readVector.add(currVectorCopy);
                    strengthSum += currStrength;
                }
            }
            return readVector;
        }
        throw new RuntimeException("The neural queue is empty!!");
    }

    public FragmentedNeuralQueue copy() {
        FragmentedNeuralQueue duplicate = new FragmentedNeuralQueue();
        duplicate.fragmentStrength = this.fragmentStrength;
        duplicate.totalStrength = this.totalStrength;
        strengthList.forEach((strength) -> {
            duplicate.strengthList.add(strength);
        });
        vectorList.forEach((vector) -> {
            duplicate.vectorList.add(vector.copy());
        });
        return duplicate;
    }

    public List<Integer> getFeatureIndexes() {
        ArrayList<Integer> queueIndexes = new ArrayList<>();
        for (int i = 0; i < strengthList.size(); i++) {
            if (strengthList.get(i) > 0.1) {
                queueIndexes.add(i);
            }
        }
        return queueIndexes;
    }

    public void basicInterpolate(FragmentedNeuralQueue target, double ipStrength) {
        List<Integer> queueIndexes = getFeatureIndexes();
        ArrayList<Integer> refQueueIndexes = new ArrayList<>();
        for (int i = 0; i < target.strengthList.size(); i++) {
            if (target.strengthList.get(i) > 0.1) {
                refQueueIndexes.add(i);
            }
        }
        int refFeatureIndex = 0;
        for (Integer index : queueIndexes) {
            //this vector will become the diff multiplied by the ipStrength
            AVector diff = target.vectorList.get(refQueueIndexes.get(refFeatureIndex)).copy();
            diff.sub(vectorList.get(index));
            refFeatureIndex = (refFeatureIndex + 1) % refQueueIndexes.size();
            diff.multiply(ipStrength);
            vectorList.get(index).add(diff);
        }
    }

    /**
     * Removes an element of the queue in first-in, first-out order.
     */
    public AVector dequeueStep() {
        AVector result = this.peek();
        vectorList.remove(0);
        totalStrength -= strengthList.remove(0);
        return result;
    }

    public void initFromFile(String filePath) {
        try {
            BufferedReader reader = new BufferedReader(new FileReader(filePath));
            Object[] lines = reader.lines().toArray();
            String contents = "";
            for (int i = 0; i < lines.length; i++) {
                contents += (String) lines[i];
                if (i < lines.length - 1) {
                    contents += "\n";
                }
            }
            String strengthLabel = "(strengths)\n";
            String vectorLabel = "(vectors)\n";
            int strengthStart = contents.indexOf(strengthLabel) + strengthLabel.length();
            int strengthEnd = contents.indexOf(vectorLabel);
            int vectorStart = strengthEnd + vectorLabel.length();
            String strengthContents = contents.substring(strengthStart, strengthEnd);
            String vectorContents = contents.substring(vectorStart);
            AVector strengths = (AVector) ReadWriteUtilities.readNumpyCSVString(strengthContents);
            AMatrix vectors = (AMatrix) ReadWriteUtilities.readNumpyCSVString(vectorContents);

            initFromData(strengths, vectors);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void writeToFile(String filePath) {
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter(filePath));
            writer.write("(strengths)");
            writer.newLine();
            AVector strengthData = Vector.create(strengthList);
            writer.write(ReadWriteUtilities.getNumpyCSVString(strengthData));
            writer.write("(vectors)");
            writer.newLine();
            AVector[] vectorArray = new AVector[vectorList.size()];
            AMatrix vectorData = Matrix.create(vectorList.toArray(vectorArray));
            writer.write(ReadWriteUtilities.getNumpyCSVString(vectorData));
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void initFromData(AVector strengths, AMatrix vectors) {
        strengthList.clear();
        vectorList.clear();
        for (int i = 0; i < strengths.length(); i++) {
            strengthList.add(strengths.get(i));
        }
        for (int i = 0; i < vectors.rowCount(); i++) {
            vectorList.add(vectors.getRow(i));
        }
    }
}
