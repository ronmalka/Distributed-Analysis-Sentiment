
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

//check if one of the instance are "impaired" , hence fail.
public class StatusService implements Runnable {
    private AmazonEC2Client ec2Client;
    private List<Instance> workers;
    public static AtomicBoolean Done = new AtomicBoolean(false);

    public StatusService(AmazonEC2Client ec2Client, List<Instance> workers) {
        this.ec2Client = ec2Client;
        this.workers = workers;
    }

    @Override
    public void run() {
        while(!Done.get()) {
            DescribeInstanceStatusResult result = ec2Client.describeInstanceStatus(new DescribeInstanceStatusRequest()
                    .withIncludeAllInstances(true));
            for (InstanceStatus status : result.getInstanceStatuses()) {
                String instanceStatus = status.getInstanceStatus().getStatus();
                String systemStatus = status.getSystemStatus().getStatus();
                if ("impaired".equals(instanceStatus) || "impaired".equals(systemStatus)) {
                    String instanceToTerminate = status.getInstanceId();
                    ec2Client.terminateInstances(new TerminateInstancesRequest().withInstanceIds(instanceToTerminate));
                    //replace with new one
                    Manager.createNewWorkers(1);
                    for (Instance instance : workers) {
                        if (instance.getInstanceId().equals(instanceToTerminate)) {
                            workers.remove(instance);
                            break;
                        }
                    }
                }
            }
            try {
                Thread.sleep(60*1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
