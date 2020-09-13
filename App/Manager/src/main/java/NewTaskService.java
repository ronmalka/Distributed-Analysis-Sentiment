

import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ConcurrentMap;

public class NewTaskService implements Runnable {

    private  final String KEY = "key" ,  REVIEW = "review", ID = "id";
    private String bucketName , managerToLocal, managerToWorker,workersToManager, key , id ;
    private int messagePerWorker;
    private AmazonSQSClient amazonSQSClient;
    private AmazonS3Client amazonS3Client;
    private List<Instance> workers;
    private ConcurrentMap<String, Task> tasks;

    public NewTaskService(String bucketName, String managerToWorker,String workersToManager,
                          AmazonSQSClient amazonSQSClient, AmazonS3Client amazonS3Client,
                          List<Instance> workers, ConcurrentMap<String, Task> tasks, Map <String, MessageAttributeValue> messageAttributeValueMap) {
        this.bucketName = bucketName;
        this.managerToWorker = managerToWorker;
        this.amazonSQSClient = amazonSQSClient;
        this.amazonS3Client = amazonS3Client;
        this.workersToManager = workersToManager;
        this.workers = workers;
        this.tasks = tasks;

        id = "task" + UUID.randomUUID().toString();

        managerToLocal = messageAttributeValueMap.get("OUTPUT_SQS").getStringValue();
        key = messageAttributeValueMap.get("KEY").getStringValue();
        messagePerWorker=Integer.parseInt(messageAttributeValueMap.get("MESSAGES_PER_WORKER").getStringValue());

    }

    @Override
    public void run() {
        System.out.println("******************NewTaskService: run***************************");
        int msgCounter = processTask();
        tasks.put(id,new Task(managerToLocal, msgCounter));
        Manager.setTasksMap(tasks);
        Thread responseService = new Thread(new ResponseService(
                bucketName,workersToManager,tasks,amazonS3Client,amazonSQSClient,id,msgCounter
        ));
        responseService.start();
        int neededWorkers = (msgCounter/messagePerWorker == 0)? 1 :msgCounter/messagePerWorker;
        createWorkers(neededWorkers);
    }
    //create workers instance
    private void createWorkers(int neededWorkers) {
        System.out.println("******************NewTaskService: createWorkers***************************");
        // consider the AWS limitation for amount of new instances
        int limitedWorkers = (neededWorkers >= 16) ? 15 : neededWorkers;
        int workersToCreate = limitedWorkers - workers.size();
        if (workersToCreate > 0) {
            Manager.createNewWorkers(workersToCreate);
        }
    }

    // create tasks to the workers, send them and returns the number of tasks created
    private int processTask() {
        System.out.println("******************NewTaskService: processTask***************************");
        S3Object input = amazonS3Client.getObject(new GetObjectRequest(bucketName, key));
        BufferedReader reader = new BufferedReader(new InputStreamReader(input.getObjectContent()));

        int msgCounter = 0;
        while (true) {
            String line = null;
            try {
                line = reader.readLine();
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (line == null) break;
            ReviewParser reviewParser =  makeReview(line);
            String title = reviewParser.getTitle();
            System.out.println("Manager: send reviews");
            for (Review review : reviewParser.getReviews()){
                sendMessage(review,title,msgCounter);
                msgCounter++;
            }

        }
        System.out.println("NewTaskService: messageCount: " + msgCounter);
        return msgCounter;
    }


    private ReviewParser makeReview(String line) {
        System.out.println("Manager: makeReview");
        Gson gson = new Gson();
        ReviewParser reviewParser = gson.fromJson(line, ReviewParser.class);
        return reviewParser;
    }

    private void sendMessage(Review review, String title, int msgCounter) {
        System.out.println("******************NewTaskService: sendMessage***************************");
        Map<String,MessageAttributeValue> messageAttributeValueMap =
                new HashMap<String, MessageAttributeValue>();

        messageAttributeValueMap.put(REVIEW , new MessageAttributeValue()
                .withDataType("String")
                .withStringValue(review.getText()));
        messageAttributeValueMap.put("reviewId" , new MessageAttributeValue()
                .withDataType("String")
                .withStringValue(review.getId()));
        messageAttributeValueMap.put("reviewTitle" , new MessageAttributeValue()
                .withDataType("String")
                .withStringValue(title));
        messageAttributeValueMap.put("reviewToStringEntity" , new MessageAttributeValue()
                .withDataType("String")
                .withStringValue(review.toStringEntity()));
        messageAttributeValueMap.put("reviewToString" , new MessageAttributeValue()
                .withDataType("String")
                .withStringValue(review.toStringEntity()));
        messageAttributeValueMap.put(ID , new MessageAttributeValue()
                .withDataType("String")
                .withStringValue(id));
        messageAttributeValueMap.put("MessageId" , new MessageAttributeValue()
                .withDataType("String")
                .withStringValue(String.valueOf(msgCounter)));

        // send a new sqs msg with the details of the file
        SendMessageRequest sendMsgRequest = new SendMessageRequest(managerToWorker, "new review task")
                .withMessageAttributes(messageAttributeValueMap);
        amazonSQSClient.sendMessage(sendMsgRequest);
    }


    public class Task{

        private String managerToLocal;
        private int unfinishedJobs;
        private boolean isSent, isDone;

        public Task(String managerToLocalSQS, int unfinishedJobs) {
            this.managerToLocal = managerToLocalSQS;
            this.unfinishedJobs = unfinishedJobs;
        }
        public String getManagerToLocalSQS() {
            return managerToLocal;
        }
        public void decrease(){

            unfinishedJobs--;
            isDone = unfinishedJobs == 0;

        }

        public boolean isDone() {
            return isDone;
        }

        public void Sent(){isSent = true;}
        public boolean isSent() {
            return isSent;
        }

    }
}
