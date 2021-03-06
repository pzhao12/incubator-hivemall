/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package hivemall.fm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

import javax.annotation.Nonnull;

import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorFactory;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspectorUtils;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.PrimitiveObjectInspectorFactory;
import org.junit.Assert;
import org.junit.Test;

public class FieldAwareFactorizationMachineUDTFTest {

    private static final boolean DEBUG = false;
    private static final int ITERATIONS = 50;
    private static final int MAX_LINES = 200;

    @Test
    public void testSGD() throws HiveException, IOException {
        runTest("Pure SGD test", "-opt sgd -classification -factors 10 -w0 -seed 43", 0.60f);
    }

    @Test
    public void testAdaGrad() throws HiveException, IOException {
        runTest("AdaGrad test", "-opt adagrad -classification -factors 10 -w0 -seed 43", 0.30f);
    }

    @Test
    public void testAdaGradNoCoeff() throws HiveException, IOException {
        runTest("AdaGrad No Coeff test",
            "-opt adagrad -no_coeff -classification -factors 10 -w0 -seed 43", 0.30f);
    }

    @Test
    public void testFTRL() throws HiveException, IOException {
        runTest("FTRL test", "-opt ftrl -classification -factors 10 -w0 -seed 43", 0.30f);
    }

    @Test
    public void testFTRLNoCoeff() throws HiveException, IOException {
        runTest("FTRL Coeff test", "-opt ftrl -no_coeff -classification -factors 10 -w0 -seed 43",
            0.30f);
    }

    private static void runTest(String testName, String testOptions, float lossThreshold)
            throws IOException, HiveException {
        println(testName);

        FieldAwareFactorizationMachineUDTF udtf = new FieldAwareFactorizationMachineUDTF();
        ObjectInspector[] argOIs = new ObjectInspector[] {
                ObjectInspectorFactory.getStandardListObjectInspector(PrimitiveObjectInspectorFactory.javaStringObjectInspector),
                PrimitiveObjectInspectorFactory.javaDoubleObjectInspector,
                ObjectInspectorUtils.getConstantObjectInspector(
                    PrimitiveObjectInspectorFactory.javaStringObjectInspector, testOptions)};

        udtf.initialize(argOIs);
        FieldAwareFactorizationMachineModel model = udtf.initModel(udtf._params);
        Assert.assertTrue("Actual class: " + model.getClass().getName(),
            model instanceof FFMStringFeatureMapModel);

        double loss = 0.d;
        double cumul = 0.d;
        for (int trainingIteration = 1; trainingIteration <= ITERATIONS; ++trainingIteration) {
            BufferedReader data = readFile("bigdata.tr.txt.gz");
            loss = udtf._cvState.getCumulativeLoss();
            int lines = 0;
            for (int lineNumber = 0; lineNumber < MAX_LINES; ++lineNumber, ++lines) {
                //gather features in current line
                final String input = data.readLine();
                if (input == null) {
                    break;
                }
                String[] featureStrings = input.split(" ");

                double y = Double.parseDouble(featureStrings[0]);
                if (y == 0) {
                    y = -1;//LibFFM data uses {0, 1}; Hivemall uses {-1, 1}
                }

                final List<String> features = new ArrayList<String>(featureStrings.length - 1);
                for (int j = 1; j < featureStrings.length; ++j) {
                    String[] splitted = featureStrings[j].split(":");
                    Assert.assertEquals(3, splitted.length);
                    int index = Integer.parseInt(splitted[1]) + 1;
                    String f = splitted[0] + ':' + index + ':' + splitted[2];
                    features.add(f);
                }
                udtf.process(new Object[] {features, y});
            }
            cumul = udtf._cvState.getCumulativeLoss();
            loss = (cumul - loss) / lines;
            println(trainingIteration + " " + loss + " " + cumul / (trainingIteration * lines));
            data.close();
        }
        println("model size=" + udtf._model.getSize());
        Assert.assertTrue("Last loss was greater than expected: " + loss, loss < lossThreshold);
    }

    @Nonnull
    private static BufferedReader readFile(@Nonnull String fileName) throws IOException {
        InputStream is = FieldAwareFactorizationMachineUDTFTest.class.getResourceAsStream(fileName);
        if (fileName.endsWith(".gz")) {
            is = new GZIPInputStream(is);
        }
        return new BufferedReader(new InputStreamReader(is));
    }

    private static void println(String line) {
        if (DEBUG) {
            System.out.println(line);
        }
    }

}
