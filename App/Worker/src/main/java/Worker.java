/*
* A worker process resides on an EC2 node. Its life cycle is as follows:
Repeatedly:

Get a message from an SQS queue.
Perform the requested job, and return the result.
remove the processed message from the SQS queue.

IMPORTANT:
If a worker stops working unexpectedly before finishing its work on a message,
* then some other worker should be able to handle that message.*/

import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.google.gson.Gson;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.neural.rnn.RNNCoreAnnotations;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.sentiment.SentimentCoreAnnotations;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.util.CoreMap;


import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;

public class Worker {

    private final static String REGION = "us-east-1" , REVIEW = "review", ID = "id" ;

    private static AmazonS3Client amazonS3Client;
    private static AmazonSQS amazonSQS;

    private static String bucketName , managerToWorkers, workersToManager, id;

    private static int counter =0;

    public static void main(String[] args) {
        managerToWorkers = args[0];
        workersToManager = args[1];
        bucketName = args[2];

        BuildAws();

        run();

    }

    private static void BuildAws() {

        System.out.println("Worker: BuildAws");

        //BuildS3Client;
        amazonS3Client = (AmazonS3Client) AmazonS3ClientBuilder.standard()
                .withRegion(REGION)
                .build();
        //BuildSQS
        amazonSQS = AmazonSQSClientBuilder.standard()
                .withRegion(REGION)
                .build();
    }

    private static void run(){
        System.out.println("Worker: run");

        while(true) {

            final Message message = getMessage();

            if( message != null ) {
                counter++;
                int messageId = Integer.parseInt(message.getMessageAttributes().get("MessageId").getStringValue());
                System.out.println("Worker done " + counter + " reviews");
                id = message.getMessageAttributes().get(ID).getStringValue();
                String review = message.getMessageAttributes().get(REVIEW).getStringValue();
                String reviewId = message.getMessageAttributes().get("reviewId").getStringValue();
                String reviewToStringEntity = message.getMessageAttributes().get("reviewToStringEntity").getStringValue();
                String reviewToString =  message.getMessageAttributes().get("reviewToString").getStringValue();
                String reviewTitle =  message.getMessageAttributes().get("reviewTitle").getStringValue();

                try {

                    executeTask(review, reviewId, reviewToStringEntity,reviewToString,reviewTitle,messageId);

                } catch (Exception e) {

                    String messageERROR = id + e.getMessage();
                    sendMessage(messageERROR);
                }
                finally {
                    amazonSQS.deleteMessage(managerToWorkers, message.getReceiptHandle());
                }
            }
            else{
                System.out.println("Work is done!");
            }
        }

    }

    private static void executeTask(String review, String reviewId, String reviewToStringEntity, String reviewToString, String reviewTitle, int messageId) {
        StringBuilder stringBuilder = new StringBuilder("");

            String sentimentColor = findSentimentColor(review);
            String entityToPrint = makeAndPrintEntity(reviewToStringEntity);
            String backGroundColor = "background-color:" + sentimentColor;
            stringBuilder
                    .append("<div style=" + backGroundColor +" >")
                    .append("<h4> Name: "+ reviewTitle + " </h4>")
                    .append(reviewToString)
                    .append("<p>" + entityToPrint + "</p>")
                    .append("</div>");


        sendMessage(stringBuilder.toString() ,reviewId,messageId);
    }

    private static void sendMessage(String toString, String reviewId , int messageId) {
        System.out.println("Worker: sendMessage");
        amazonSQS.sendMessage(new SendMessageRequest(workersToManager, toString)
                .withMessageAttributes(new HashMap<String, MessageAttributeValue>(){{
                    put(ID, new MessageAttributeValue().withDataType("String").withStringValue(id));
                    put("reviewId",new MessageAttributeValue().withDataType("String").withStringValue(reviewId));
                    put("MessageId", new MessageAttributeValue().withDataType("String").withStringValue(String.valueOf(messageId)));
                }}));
    }


    private static Message getMessage() {
        System.out.println("Worker: getMessage");
        Integer timeout = 600;
        ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(managerToWorkers)
                .withMaxNumberOfMessages(1)
                .withMessageAttributeNames(REVIEW)
                .withMessageAttributeNames("reviewId")
                .withMessageAttributeNames("reviewTitle")
                .withMessageAttributeNames("reviewToStringEntity")
                .withMessageAttributeNames("reviewToString")
                .withMessageAttributeNames("MessageId")
                .withMessageAttributeNames(ID);


        List<Message> messages = amazonSQS.receiveMessage(receiveMessageRequest).getMessages();

        if (!messages.isEmpty()){
            Message message = messages.get(0);
            // Get the receipt handle for the first message in the queue.
            String receipt = message.getReceiptHandle();
            amazonSQS.changeMessageVisibility(managerToWorkers, receipt, timeout);
            return message;
        }
        return null;
    }

    private static String findSentimentColor(String text) {
        System.out.println("Worker: findSentimentColor");
        int sentiment = findSentiment(text);
        return getColor(sentiment);

    }

    private static String getColor(int sentiment) {
        System.out.println("Worker: getColor");
        switch (sentiment) {
            case 0:
                return "darkred";
            case 1:
                return "red";
            case 2:
                return "black";
            case 3:
                return  "lightgreen";
            case 4:
                return "darkgreen";
            default:
                return "white";
        }

    }

    private static String makeAndPrintEntity(String reviewToStringEntity) {
        System.out.println("Worker: makeAndPrintEntity");
        String entityToPrint = printEntities(reviewToStringEntity);
        String entities [] = entityToPrint.split("\\s+");
        StringBuilder result = new StringBuilder("[");
        for (int i=1 ; i < entities.length-1 ; i ++)
            if (!entities[i].equals("-::O"))
                result.append(entities[i]).append(", ");
        result.append(entities[entities.length-1] + "]");
        return result.toString();
    }

    private static void sendMessage(String message){
        System.out.println("Worker: sendMessage");
        amazonSQS.sendMessage(new SendMessageRequest(workersToManager, message)
                .withMessageAttributes(new HashMap<String, MessageAttributeValue>(){{
                    put(ID, new MessageAttributeValue().withDataType("String").withStringValue(id));
                }}));
    }

    public static int findSentiment(String review) {
        Properties props = new Properties();
        props.put("annotators", "tokenize, ssplit, parse, sentiment");
        props.setProperty("ner.useSUTime", "false");
        StanfordCoreNLP sentimentPipeline = new StanfordCoreNLP(props);

        int mainSentiment = 0;
        if (review != null && review.length() > 0) {
            int longest = 0;
            Annotation annotation = sentimentPipeline.process(review);
            for (CoreMap sentence : annotation
                    .get(CoreAnnotations.SentencesAnnotation.class)) {
                Tree tree = sentence
                        .get(SentimentCoreAnnotations.SentimentAnnotatedTree.class);
                int sentiment = RNNCoreAnnotations.getPredictedClass(tree);
                String partText = sentence.toString();
                if (partText.length() > longest) {
                    mainSentiment = sentiment;
                    longest = partText.length();
                }

            }
        }
        return mainSentiment;
    }

    public static String printEntities(String review) {

        Properties props = new Properties();
        props.put("annotators", "tokenize , ssplit, pos, lemma, ner");
        props.setProperty("ner.useSUTime", "false");
        StanfordCoreNLP NERPipeline = new StanfordCoreNLP(props);
        StringBuilder result = new StringBuilder("");
        // create an empty Annotation just with the given text
        Annotation document = new Annotation(review);

        // run all Annotators on this text
        NERPipeline.annotate(document);

        // these are all the sentences in this document
        // a CoreMap is essentially a Map that uses class objects as keys and has values with custom types
        List<CoreMap> sentences = document.get(CoreAnnotations.SentencesAnnotation.class);

        for (CoreMap sentence : sentences) {
            // traversing the words in the current sentence
            // a CoreLabel is a CoreMap with additional token-specific methods
            for (CoreLabel token : sentence.get(CoreAnnotations.TokensAnnotation.class)) {
                // this is the text of the token
                String word = token.get(CoreAnnotations.TextAnnotation.class);
                // this is the NER label of the token
                String ne = token.get(CoreAnnotations.NamedEntityTagAnnotation.class);
                result.append("\t-" + word + ":" + ne);
                System.out.println("\t-" + word + ":" + ne);
            }
        }
        return result.toString();
    }

}
