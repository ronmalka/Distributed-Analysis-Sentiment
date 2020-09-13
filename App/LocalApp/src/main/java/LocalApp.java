/*
The application resides on a local (non-cloud) machine.
Once started, it reads the input file from the user, and:
Checks if a Manager node is active on the EC2 cloud.
If it is not, the application will start the manager node.
Uploads the file to S3.
Sends a message to an SQS queue, stating the location of the file on S3
Checks an SQS queue for a message indicating the process is done and the response (the summary file) is available on S3.
Downloads the summary file from S3, and create an html file representing the results.
Sends a termination message to the Manager if it was supplied as one of its input arguments.

IMPORTANT: There can be more than one than one local application running at the same time, and requesting service from the manager.
*/

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.*;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.*;
import com.amazonaws.services.sqs.model.Message;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;

import java.io.*;
import java.util.*;
public class LocalApp {
    private static final  String IMG_NAME="ami-0107010a6487fac19" , IAM_NAME = "Manager" ,
            REGION = "us-east-1", KEY_NAME = "" , INPUT_QUEUE = "InputQueue", bucketJarsUrl = "jarsbucket1992";


    private static AmazonEC2 amazonEC2;
    private static AmazonS3Client amazonS3Client;
    private static AmazonSQS amazonSQS;


    private static String inputFileName , outputFileName , localToManager , managerToLocal, managerInstanceId, bucketName;

    private static int numOfMessages;
    private static boolean terminate;



    public static void main(String[] args) {
        int sizeOfParams = 0;
        //assume that inputs.length = outputs.length
        if (args[args.length-1].equals("terminate")){
            terminate =true;
            sizeOfParams = (args.length-2)/2;
            numOfMessages = Integer.parseInt(args[args.length-2]);
        }
        else {
            terminate =false;
            sizeOfParams = (args.length-1)/2;
            numOfMessages = Integer.parseInt(args[args.length-1]);
        }

        BuildAws();
        CheckAndWakeManager();

        for (int i = 0; i < sizeOfParams; i++){
            inputFileName = args[i];
            outputFileName = args[i + sizeOfParams];
            sendMessageToManager();
            receiveMessageFromManager();
        }
        if (terminate) sendTerminateMessage();



    }

    private static void BuildAws() {
        System.out.println("BuildAws");

        AWSCredentialsProvider credentialsProvider = new AWSStaticCredentialsProvider(new ProfileCredentialsProvider().getCredentials());

        //BuildEC2;
        amazonEC2 = AmazonEC2ClientBuilder.standard()
                .withCredentials(credentialsProvider)
                .withRegion(REGION)
                .build();
        //BuildS3Client;
        amazonS3Client = (AmazonS3Client) AmazonS3ClientBuilder.standard()
                .withCredentials(credentialsProvider)
                .withRegion(REGION)
                .build();
        //BuildSQS
        amazonSQS = AmazonSQSClientBuilder.standard()
                .withCredentials(credentialsProvider)
                .withRegion(REGION)
                .build();
    }

    private static void CheckAndWakeManager() {

        System.out.println("CheckAndWakeManager");
        String queueName = "ManagerToLocalApplication" + UUID.randomUUID().toString();
        CreateQueueRequest ManagerToLocalRequest = new CreateQueueRequest(
                queueName
        );
        System.out.println("Manager to Local SQS: " + queueName);
        managerToLocal = amazonSQS.createQueue(ManagerToLocalRequest).getQueueUrl();

        boolean found = false;
        Filter managerInstanceFilter = new Filter("tag:type",
                new ArrayList<String>(Collections.singletonList(IAM_NAME)));
        DescribeInstancesRequest request = new DescribeInstancesRequest()
                .withFilters(managerInstanceFilter);
        DescribeInstancesResult response = amazonEC2.describeInstances(request);

        for(Reservation reservation : response.getReservations()) {
            for(Instance instance : reservation.getInstances()) {

                String instanceState = instance.getState().getName();
                if (!instanceState.equals("pending")&&!instanceState.equals("terminated") && !instanceState.equals("stopped")){
                    List <Tag> tags = instance.getTags();
                    String  localToManagertmp = "";
                    String bucketNametmp = "";
                    for (Tag tag : tags) {
                        if (tag.getValue().equals(IAM_NAME)){
                            managerInstanceId = instance.getInstanceId();
                            found = true;
                        }
                        if (tag.getKey().equals(INPUT_QUEUE))
                            localToManagertmp = tag.getValue();
                        if (tag.getKey().equals("bucketName"))
                            bucketNametmp = tag.getValue();

                    }

                    if (found){
                        System.out.println("Local to Manager SQS: " + localToManagertmp);
                        localToManager = localToManagertmp;
                        bucketName = bucketNametmp;
                        break;
                    }
                }
            }
            if (found) break;
        }

        if (!found){
            String queueName2 = "LocalApplicationToManager"+ UUID.randomUUID().toString();
            System.out.println("Local to Manager SQS: " + queueName2);
            CreateQueueRequest LocalApplicationToManagerRequest = new CreateQueueRequest(queueName2);
            localToManager = amazonSQS.createQueue(LocalApplicationToManagerRequest).getQueueUrl();
            createManager();
        }

    }

    private static void createManager() {
        System.out.println("createManager");
        bucketName = "ass1bucket" + UUID.randomUUID().toString();
        System.out.println("bucket name: " + bucketName);
        amazonS3Client.createBucket(bucketName);
        try {
            RunInstancesRequest runInstancesRequest = new RunInstancesRequest(IMG_NAME,1,1)
                    .withInstanceType(InstanceType.T2Large.toString())
                    .withUserData(makeManagerScript())
                    .withImageId(IMG_NAME)
                    .withIamInstanceProfile(new IamInstanceProfileSpecification()
                            .withArn(""))
                    .withKeyName(KEY_NAME);

            Instance instance = amazonEC2.runInstances(runInstancesRequest)
                    .getReservation()
                    .getInstances()
                    .get(0);
            managerInstanceId = instance.getInstanceId();

            CreateTagsRequest createTagsRequest = new CreateTagsRequest()
                    .withTags(new Tag("type",IAM_NAME))
                    .withTags(new Tag(INPUT_QUEUE, localToManager))
                    .withTags(new Tag("bucketName", bucketName))
                    .withResources(instance.getInstanceId());

            amazonEC2.createTags(createTagsRequest);

        }catch (AmazonServiceException ase){

            System.out.println("Caught Exception: " + ase.getMessage());
            System.out.println("Reponse Status Code: " + ase.getStatusCode());
            System.out.println("Error Code: " + ase.getErrorCode());
            System.out.println("Request ID: " + ase.getRequestId());

        }

    }
    private static String makeManagerScript() {
        System.out.println("makeManagerScript");

        String jarName = "Manager.jar";

        String userDataScript = "";
        userDataScript = userDataScript + "#!/bin/bash" + "\n";
       // userDataScript = userDataScript + "sudo wget https://s3.amazonaws.com/jarsbucket1992/Manager.jar -O Manager1.jar" + "\n";
        userDataScript = userDataScript + "cd home/ec2-user/" + "\n";
        userDataScript = userDataScript + "java -jar ./" + jarName + " " + localToManager + " " + bucketName ;
        String base64UserData = null;
        try {
            base64UserData = new String( Base64.encodeBase64( userDataScript.getBytes( "UTF-8" )), "UTF-8" );
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return base64UserData;


    }

    private static void sendMessageToManager() {
        System.out.println("sendMessageToManager");

        String uploadedFileKey = uploadToS3();

        Map<String, MessageAttributeValue> messageAttributes =
                new HashMap<>();

        messageAttributes.put("TASK_TYPE" , new MessageAttributeValue()
                .withDataType("String")
                .withStringValue("newTask"));

        messageAttributes.put("KEY" , new MessageAttributeValue()
                .withDataType("String")
                .withStringValue(uploadedFileKey));

        messageAttributes.put("OUTPUT_SQS" , new MessageAttributeValue()
                .withDataType("String")
                .withStringValue(String.valueOf(managerToLocal)));

        messageAttributes.put("MESSAGES_PER_WORKER",new MessageAttributeValue()
                .withDataType("Number")
                .withStringValue(String.valueOf(numOfMessages)));
        messageAttributes.put("INSTANCE_ID", new MessageAttributeValue()
                .withDataType("String")
                .withStringValue(managerInstanceId));

        SendMessageRequest sendMessageRequest = new SendMessageRequest(localToManager,"New Task")
                .withMessageAttributes(messageAttributes);

        amazonSQS.sendMessage(sendMessageRequest);
    }

    private static String uploadToS3() {
        System.out.println("uploadToS3");

        File file = new File(inputFileName.toLowerCase());
        String fileKeyToUpload = file.getName()
                .replace('\\', '_')
                .replace('/','_')
                .replace(':','_');
        PutObjectRequest putObjectRequest = new PutObjectRequest(bucketName,fileKeyToUpload,file)
                .withCannedAcl(CannedAccessControlList.PublicRead);

        amazonS3Client.putObject(putObjectRequest);

        return fileKeyToUpload;

    }
    private static void receiveMessageFromManager() {
        System.out.println("receiveMessageFromManager");
        // Get the receipt handle for the first message in the queue.
//        String receipt = sqs.receiveMessage(queue_url)
//                .getMessages()
//                .get(0)
//                .getReceiptHandle();
//
//        sqs.changeMessageVisibility(queue_url, receipt, timeout);
        while( true ) {

            Message message = null;
            ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(managerToLocal)
                    .withMaxNumberOfMessages(1)
                    .withMessageAttributeNames("KEY");
            List<Message> messages = new ArrayList<>();
            if (amazonSQS !=null){
                messages  = amazonSQS.receiveMessage(receiveMessageRequest).getMessages();
            }

            if (!messages.isEmpty())
                message = messages.get(0);

            if (message != null) {

                Map<String, MessageAttributeValue> msgAttributes = message.getMessageAttributes();
                String keyValue = msgAttributes.get("KEY").getStringValue();

                S3Object summary = amazonS3Client.getObject(new GetObjectRequest(bucketName, keyValue));

                downLoadHtml(summary);

                amazonSQS.deleteMessage(managerToLocal, message.getReceiptHandle());
                break;
            }
        }
    }

    private static void downLoadHtml(S3Object summary) {
        System.out.println("downLoadHtml");

        String result = S3ObjectToHtmlString(summary);

        final File file = new File(outputFileName);

        try {
            FileUtils.writeStringToFile(file, result);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }



    private static String S3ObjectToHtmlString(S3Object summary) {
        System.out.println("S3ObjectToHtmlString");
        BufferedReader reader = new BufferedReader(new InputStreamReader(summary.getObjectContent()));
        StringBuilder result = new StringBuilder();

        while (true) {
            String line;
            try {
                line = reader.readLine();
                if (line == null)
                    break;
                result.append(line).append("\n");

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return result.toString();

    }

    private static void sendTerminateMessage() {
        System.out.println("sendTerminateMessage");

        Map<String, MessageAttributeValue> messageAttributes =
                new HashMap<>();

        messageAttributes.put("TASK_TYPE" , new MessageAttributeValue()
                .withDataType("String")
                .withStringValue("terminate"));


        SendMessageRequest sendMessageRequest = new SendMessageRequest(localToManager,"terminate")
                .withMessageAttributes(messageAttributes);

        amazonSQS.sendMessage(sendMessageRequest);
        amazonSQS.deleteQueue(managerToLocal);
    }
}
