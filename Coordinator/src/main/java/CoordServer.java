import Hash.*;
import com.google.protobuf.ByteString;

import java.io.*;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class CoordServer {

    private static boolean running = true;
    private volatile static HashMap<String, StorageNode> storageNodeInfoList = new HashMap<String, StorageNode>();
    private static AtomicInteger nodeId = new AtomicInteger(0);
    private static int totAvailableSpace = 0;
    private static int totRequestsHandled = 0;
    private static BalancedHashRing balancedHashRing;
    private static int port;
    private static boolean existingCluster = false;

    public static void main(String[] args) {
        ServerSocket serve = null;

        if(args.length == 2){
            if(args[0].equals("-port")){
                port = Integer.parseInt(args[1]);
            }
        }else if (args.length == 6){
            if(args[0].equals("-port") && args[2].equals("-SnIp") && args[4].equals("-SnPort")){
                existingCluster = true;
                port = Integer.parseInt(args[1]);
            }
        }else {
            System.out.println("Invalid arguments.");
            System.exit(1);
        }
        System.out.println("Starting coordinator server on port " + port + "...");

        try {
            serve = new ServerSocket(port);

        } catch (IOException e) {
            e.printStackTrace();
            System.out.println("Failed to start server");

        }

        SHA1 sha1 = new SHA1();
        balancedHashRing = new BalancedHashRing(sha1);
        readMessages(serve, storageNodeInfoList, nodeId);

        //If coordinator comes back up, then start adding all storage nodes.
        if(existingCluster){
            System.out.println("Joining existing cluster, adding existing ring. ");
            Clientproto.SNReceive message = Clientproto.SNReceive.newBuilder().setType(Clientproto.SNReceive.packetType.RECOVER).build();
            try {
                Socket socket = new Socket(args[3], Integer.parseInt(args[5]));
                OutputStream outstream = socket.getOutputStream();
                InputStream instream = socket.getInputStream();
                message.writeDelimitedTo(outstream);
                Clientproto.CordResponse reply = Clientproto.CordResponse.parseDelimitedFrom(instream);
                try {
                    addAllNodes(reply);
                } catch (HashTopologyException e) {
                    e.printStackTrace();
                }
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }


        //Prints the ring every 10th second
        for(int i = 0; i < 100; i++){
            System.out.println("------------My ring-------------");

            ArrayList<HashRingEntry> hashRingEntries = new ArrayList<>(balancedHashRing.getEntryMap().values());
            for(HashRingEntry entry : hashRingEntries){
                System.out.println(entry.getIp() + ":" + entry.getPort() + " id " +entry.getNodeId() + " Neigborid " + entry.getNeighbor().getNodeId() + " --> " + entry.getPosition());
            }

            System.out.println("-------------------------");
            try {
                TimeUnit.SECONDS.sleep(10);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

        }
    }



    /**
     * Method that runs in separate thread from main and waits for incoming messages at a port.
     * If a message is recived it creates a new thread to handle it and continues to listen for new messages
     * @param serve ServerSocket object
     */
    private static void readMessages(final ServerSocket serve, final HashMap<String, StorageNode> storageNodeInfos, final AtomicInteger nodeId){
        final Runnable run = new Runnable() {
            public void run() {
                try {
                    while(running) {
                        Socket sock = serve.accept();
                        //Create thread to handle request
                        MessageListener messageListener = new MessageListener(sock, storageNodeInfos, nodeId,totRequestsHandled,totAvailableSpace, balancedHashRing);
                        messageListener.start();
                    }
                } catch(IOException ioe) {
                    ioe.printStackTrace();
                }
            }
        };
        new Thread(run).start();
    }

    private static void addAllNodes(Clientproto.CordResponse reply) throws HashTopologyException {
        TreeMap<BigInteger, Clientproto.NodeInfo> tempMap = new TreeMap<>();

        for(Clientproto.NodeInfo node: reply.getNewNodesList()) {
            ByteString bytes = node.getPosition().getPosition();
            BigInteger position = new BigInteger(bytes.toByteArray());
            tempMap.put(position, node);
            StorageNode storageNode = new StorageNode(storageNodeInfoList, balancedHashRing, node.getId(), node.getIp(), node.getPort(), position);
            storageNodeInfoList.put(node.getIp()+node.getPort(), storageNode);
        }

        for(Map.Entry<BigInteger,Clientproto.NodeInfo> entry : tempMap.entrySet()) {
            BigInteger key = entry.getKey();
            Clientproto.NodeInfo value = entry.getValue();

            try {
                balancedHashRing.addNodeWithPosition(key, value.getId(), value.getIp(), value.getPort());
            } catch (HashException e) {
                e.printStackTrace();
            }
        }
    }
}
