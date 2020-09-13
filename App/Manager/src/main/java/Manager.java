/*
The manager process resides on an EC2 node.
It checks a special SQS queue for messages from local applications.
Once it receives a message it:
If the message is that of a new task it:
    Downloads the input file from S3.
    Distributes the operations to be performed on the reviews to the workers using SQS queue/s.
    Checks the SQS message count and starts Worker processes (nodes) accordingly.
        The manager should create a worker for every n messages, if there are no running workers.
        If there are k active workers, and the new job requires m workers, then the manager should create m-k new workers, if possible.
        Note that while the manager creates a node for every n messages,
        it does not delegate messages to specific nodes.
        All of the worker nodes take their messages from the same SQS queue;
        so it might be the case that with 2n messages, hence two worker nodes,
        one node processed n+(n/2) messages, while the other processed only n/2.
    After the manger receives response messages from the workers on all the files on an input file, then it:
        Creates a summary output file accordingly,
        Uploads the output file to S3,
        Sends a message to the application with the location of the file.

If the message is a termination message, then the manager:
    Does not accept any more input files from local applications.
    However, it does serve the local application that sent the termination message.
    Waits for all the workers to finish their job, and then terminates them.
    Creates response messages for the jobs, if needed.
    Terminates.
*/




import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;

import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import org.apache.commons.codec.binary.Base64;

import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;



public class Manager {

    private static final  String IMG_NAME="ami-04e3b616b9273025d" , IAM_NAME = "Manager",
            REGION = "us-east-1", KEY_NAME = "rmaKeysPair" ,  JAR = "worker.jar",
            bucketJarsUrl = "jarsbucket1992" , stanfordJar = "stanford-corenlp-3.9.2.jar",
            stanfordModelsJar = "stanford-corenlp-3.9.2-models.jar", jollydayJar = "jollyday.jar",
            ejmlJar="ejml-0.23.jar";
    private static AmazonEC2Client amazonEC2;
    private static AmazonS3Client amazonS3Client;
    private static AmazonSQSClient amazonSQS;

    private static String localToManager,workersToManager,
            managerToWorkers,bucketName , managerInstanceId;

    private static List<Instance> workers;
    private static ConcurrentMap<String, NewTaskService.Task> tasksMap;

    private static Thread  statusService;
    private static Runnable statusRunnable;

    public static void main(String[] args) {

        localToManager = args[0];
        bucketName = args[1];
        workers = new ArrayList<>();
        tasksMap = new ConcurrentHashMap<>();

        BuildAws();
        BuildQueue();
        makeThreads();
        ExecutorService threadPool = Executors.newCachedThreadPool(Executors.defaultThreadFactory());
        run(threadPool);
        Terminate();

    }

    private static void Terminate(){
        System.out.println("Manager: Terminate");
        // wait for all workers to finish their job
        while (true) {
            boolean finish = true;
            for (Map.Entry<String, NewTaskService.Task> entry: tasksMap.entrySet()) {
                if (!entry.getValue().isSent()) {
                    finish = false;
                    break;
                }
            }
            if (finish) break;
        }
        System.out.println("Manager: terminated tasks");
        StatusService.Done.compareAndSet(false,true);

        try {
            statusService.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.out.println("Manager: terminated statusService");
        for (Instance worker : workers) {
            amazonEC2.terminateInstances(new TerminateInstancesRequest().withInstanceIds(worker.getInstanceId()));
        }
        System.out.println("Manager: terminated workers");
        // delete all queues and shutdown the sqs client
        amazonSQS.deleteQueue(managerToWorkers);
        amazonSQS.deleteQueue(workersToManager);
        amazonSQS.deleteQueue(localToManager);
        amazonSQS.shutdown();
        System.out.println("Manager: terminated amazonSQS");
        // terminates EC2
        amazonEC2.terminateInstances(new TerminateInstancesRequest().withInstanceIds(managerInstanceId));
        System.out.println("Manager Terminated");

    }

    private static void run(ExecutorService threadPool) {
        System.out.println("Manager: run");
        while (true) {
            Message message = getMessage(localToManager);
            if (message != null) {
                System.out.println("Manager: getMessage");
                if (message.getMessageAttributes().get("TASK_TYPE").getStringValue().equals("terminate")) {
                    amazonSQS.deleteMessage(localToManager, message.getReceiptHandle());
                    break;
                }
                managerInstanceId = message.getMessageAttributes().get("INSTANCE_ID").getStringValue();
                NewTaskService newTaskService = new NewTaskService(bucketName, managerToWorkers,workersToManager,
                        amazonSQS, amazonS3Client,
                        workers, tasksMap, message.getMessageAttributes());
                threadPool.execute(newTaskService);
                amazonSQS.deleteMessage(localToManager, message.getReceiptHandle());
            } // else, keep listening to new msg - the loop continues
        }
    }

    private static void makeThreads() {
        System.out.println("Manager: makeThreads");
        statusRunnable = new StatusService(amazonEC2,workers);
        statusService = new Thread(statusRunnable);
        statusService.start();
    }

    private static void BuildQueue() {
        System.out.println("Manager: BuildQueue");
        String reviewsName = "Reviews" + UUID.randomUUID().toString();
        String resultsName = "Results" + UUID.randomUUID().toString();
        managerToWorkers = amazonSQS.createQueue(reviewsName).getQueueUrl();
        workersToManager = amazonSQS.createQueue(resultsName)
                .getQueueUrl();

        System.out.println(reviewsName);
        System.out.println(resultsName);
    }

    private static void BuildAws()  {
        System.out.println("Manager: BuildAws");

        //BuildEC2;
        amazonEC2 = (AmazonEC2Client)AmazonEC2ClientBuilder.standard()
                .withRegion(REGION)
                .build();

        //BuildS3Client;
        amazonS3Client = (AmazonS3Client) AmazonS3ClientBuilder.standard()
                .withRegion(REGION)
                .build();

        //BuildSQS
        amazonSQS = (AmazonSQSClient) AmazonSQSClientBuilder.standard()
                .withRegion(REGION)
                .build();
    }

    public static void createNewWorkers(int workersToCreate) {
        System.out.println("Manager: createNewWorkers");
        RunInstancesRequest request = new RunInstancesRequest(IMG_NAME, workersToCreate, workersToCreate)
                .withInstanceType(InstanceType.T2Large.toString())
                .withUserData(makeScript())
                .withIamInstanceProfile(new IamInstanceProfileSpecification()
                        .withArn("arn:aws:iam::811230006849:instance-profile/iamRoleAss1"))
                .withKeyName(KEY_NAME);
        List<Instance> newInstances = amazonEC2.runInstances(request).getReservation().getInstances();
        workers.addAll(newInstances);
    }

    private static String makeScript() {
        System.out.println("Manager: makeScript");
        String jarName = "Worker.jar";
        String userDataScript = "";
        userDataScript = userDataScript + "#!/bin/bash" + "\n";
        userDataScript = userDataScript + "cd home/ec2-user/" + "\n";
        userDataScript = userDataScript + "java -cp .:./" + jarName + ":" + stanfordJar + ":./" + stanfordModelsJar + ":./" + ejmlJar + ":./" + jollydayJar +" Worker" + " " + managerToWorkers + " " + workersToManager + " " + bucketName;
        //java -cp .:yourjar.jar:stanford-corenlp-3.3.0.jar:stanford-corenlp-3.3.0-models.jar:ejml-0.23.jar:jollyday-0.4.7.jar MainWorkerClass
        //wget http://www.cs.bgu.ac.il/~dsps201/Main -O dsp.html
        String base64UserData = null;
        try {
            base64UserData = new String( Base64.encodeBase64( userDataScript.getBytes( "UTF-8" )), "UTF-8" );
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return base64UserData;

    }

    public static Message getMessage(String workersToManager) {

        ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(workersToManager)
                .withMaxNumberOfMessages(1)
                .withMessageAttributeNames("INSTANCE_ID")
                .withMessageAttributeNames("id")
                .withMessageAttributeNames("reviewId")
                .withMessageAttributeNames("messageId")
                .withMessageAttributeNames(Collections.singleton("All"))
                .withAttributeNames("");
        if (amazonSQS !=null){
            List<Message> messages =amazonSQS.receiveMessage(receiveMessageRequest).getMessages();
            return messages.isEmpty() ? null : messages.get(0);
        }
        return null;

    }
    public static void setTasksMap(ConcurrentMap<String, NewTaskService.Task> tasksMap) {
        Manager.tasksMap = tasksMap;
    }
}
