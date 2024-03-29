package pit;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import javax.jms.*;
import javax.naming.*;

/*
* This is the ONLY file that you are to edit.  It is the model of play for
* every PITplayer.  Each PITplayer instantiates this model and uses it to
* process the messages it receives.
*/
public class PITPlayerModel {
    
    // Each PITplayer has a unique myPlayerNumber.  It is set in the PITPlayer constructor.
    private final int myPlayerNumber;
    // Cards is this player's set of cards.
    private final ArrayList cards = new ArrayList();
    // Hand is used to record the number of each commodity cards held in hand
    private HashMap<String, Integer> hand;
    // numTrades counts trades.
    private int numTrades = 0;
    // maxTrades is the maximum number of trades, after which trading is stopped.
    //private final int maxTrades = 20000;
    private final int maxTrades = 20000;
    // numPlayers are the number of Players trading.  This comes with a NewHand from the PITsnapshot servlet
    private int numPlayers = 0;
    // halting indicates that the system is being reset, so ignore trades until a new had received
    private boolean halting = false;
    // A boolean indicates whether this player need to record the incoming messages
    private boolean startRecord = false;
    // An arraylist recording the participants who have sent this player a Marker
    private ArrayList<Integer> participants = new ArrayList<>();
    
    /* The snapshot servlet (PITsnapshot) is expecting to be passed an ObjectMessage
    * where the  object is a HashMap. Therefore this definition of HashMap is
    * provided although it  is not currently used (it is for you to use).
    * PITsnapshot is expecting a set of attibute/value pairs. These include the player
    * number, as in state.put("Player",myPlayerNumber),  and each commodity string
    * and the number of that commodity  in the snapshot.
    * Also included below is a utility method  that will convert a HashMap into a string
    * which is useful for printing diagnostic messages to  the console.
    */
    private HashMap<String, Integer> state;
    
    // PITPlayerModel constructor saves what number player this object represents.
    PITPlayerModel(int myNumber) {
        myPlayerNumber = myNumber;
        state = new HashMap<>();
        state.put("Player", myPlayerNumber);
    }
    
    public void onMessage(Message message) {
        try {
            if (message instanceof ObjectMessage) {
                Object o = ((ObjectMessage) message).getObject();
                
                /*
                * There are 6 types of messages:  Reset, NewHand, TenderOffer,
                * AcceptOffer, RejectOffer, and Marker
                */
                
                // Reset the Player.  This message is generated by the PITsnapshot servlet
                if (o instanceof Reset) {
                    doReset((Reset) o);
                    
                    // NewHand received from PITsnapshot
                } else if (o instanceof NewHand) {
                    // Add the new hand into cards
                    doNewHand((NewHand) o);
                    
                    // Receive an offer from another Player
                } else if (o instanceof TenderOffer) {
                    doReceiveTenderOffer((TenderOffer) o);
                    
                    // Another Player accepted our offer
                } else if (o instanceof AcceptOffer) {
                    doReceiveAcceptOffer((AcceptOffer) o);
                    
                    // Another Player rejected our offer
                } else if (o instanceof RejectOffer) {
                    doReceiveRejectOffer((RejectOffer) o);
                    
                } else if (o instanceof Marker) {
                    //System.out.println("Marker received");
                    doReceiveMarker((Marker) o);
                } else {
                    System.out.println("PITplayer" + myPlayerNumber + " received unknown Message type");
                    // just ignore it
                }
            }
        } catch (Exception e) {
            System.out.println("Exception thrown in PITplayer" + myPlayerNumber + ": " + e);
        }
    }
    
    private void doReset(Reset reset) throws Exception {
        // Resetting is done by two messages, first to halt, then to clear
        if (reset.action == Reset.HALT) {
            System.out.println("PITplayer" + myPlayerNumber + " received Reset HALT");
            halting = true;
            // Reply to the PITsnapshot servlet acknowledging the Reset HALT
            sendToQueue("jms/PITmonitor", reset);
        } else { // action == Reset.CLEAR
            System.out.println("PITplayer" + myPlayerNumber + " received Reset RESET");
            // Drop all cards in hand
            cards.clear();
            numTrades = 0;
            numPlayers = 0;
            halting = false;
            // Reply to the PITsnapshot servlet acknowledging the Reset
            sendToQueue("jms/PITmonitor", reset);
        }
    }
    
    private void doNewHand(NewHand nHand) throws Exception {
        // Add a new hand of cards.
        // It is actually possible that an offer from another Player has been
        // accepted already, beating the NewHand
        cards.addAll((nHand).newHand);
        numPlayers = (nHand).numPlayers;
        // Update the initial hand of this player
        hand = new HashMap<>();
        hand.put("Player", myPlayerNumber);
        for (int i = 0; i < cards.size(); i++) {
            String comm = (String)(cards.get(i));
            if (!hand.containsKey(comm)) {
                hand.put(comm, 1);
            } else {
                int newCount = hand.get(comm) + 1;
                hand.put(comm, newCount);
            }
        }
        
        System.out.println("PITplayer" + myPlayerNumber + " new hand: " + toString(cards));
        // Offer a card to another Player
        doTenderOffer();
    }
    
    private void doReceiveTenderOffer(TenderOffer trade) throws Exception {
        if (halting) {
            return; // if halting, discard trade
        }
        
        System.out.println("PITplayer" + myPlayerNumber + " received offer of: " + trade.tradeCard + " from player: " + trade.sourcePlayer);
        // Keep track of incoming messages if certain conditions are met
        keepTrack(trade.sourcePlayer, trade.tradeCard);
        
        // When receiving an offer, decide whether to Accept or Reject it
        
        // Find the the commodity whose count is the max
        int maxCount = -1;
        String maxComm = "";
        for (Map.Entry<String, Integer> entry : hand.entrySet()) {
            if (!entry.getKey().equals("Player")) {
                if (entry.getValue() > maxCount) {
                    maxCount = entry.getValue();
                    maxComm = entry.getKey();
                }
            }
        }
        System.out.println("Player" + myPlayerNumber + ": " + maxComm + " holds the maximum count at " + maxCount);
        // If the tradecard received is the one this player want
        // aka it is the commodity this player holds the most
        // Accept the offer
        if (trade.tradeCard.equals(maxComm)) {
            // Add the offer to my hand of cards
            cards.add(trade.tradeCard);
            // Update the hand
            updateHand(hand);
            
            System.out.println(toString(hand));
            // Pay with one of my cards (the commodity I hold the least)
            doReplyAccept(trade.sourcePlayer);
        } else {
            doReplyReject(trade);
        }
        
        /*if (Math.random() < 0.8) {
        // Accept the trade 80% of the time
        
        // Add the Offer to my hand of cards
        cards.add(trade.tradeCard);
        // Pay with one of my cards
        doReplyAccept(trade.sourcePlayer);
        
        } else {
        //Otherwise reject the offer and send back the card
        doReplyReject(trade);
        }*/
        
    }
    
    private void doReplyAccept(int sendTo) throws Exception {
        
        // if hit maxTrades limit, then stop sending trades
        if (maxTrades(maxTrades)) {
            return;
        }
        // If a monopoly is reached, then stop sending trades
        /*if (monopoly(hand)) {
            System.out.println("MONOPOLY REACHED! in Player" + myPlayerNumber);
            return;
        }*/
        
        // In payment for the card I just accepted, send back one of my cards.
        AcceptOffer newTrade = new AcceptOffer();
        
        // Find the commodity with the minimum count
        System.out.println(toString(hand));
        int minCount = Integer.MAX_VALUE;
        String minComm = "";
        for (Map.Entry<String, Integer> entry : hand.entrySet()) {
            if (!entry.getKey().equals("Player")) {
                if (entry.getValue() < minCount) {
                    minCount = entry.getValue();
                    minComm = entry.getKey();
                }
            }
        }
        System.out.println("Player" + myPlayerNumber + ": " + minComm + " holds the minimum count at " + minCount);
        // Find the position of the first occurence of the commodity with the minimun count
        for (int i = 0; i < cards.size(); i++) {
            String comm = (String)(cards.get(i));
            if (comm.equals(minComm)) {
                // Send this card back
                newTrade.tradeCard = (String) cards.remove(i);
                break;
            }
        }
        
        //newTrade.tradeCard = (String) cards.remove(0);
        newTrade.sourcePlayer = myPlayerNumber;
        updateHand(hand);
        // Keep track of incoming messages if certain conditions are met
        //keepTrack(newTrade.sourcePlayer, newTrade.tradeCard);
        System.out.println(toString(hand));
        
        //Send the card to the other player
        System.out.println("PITplayer" + myPlayerNumber + " accepting offer and paying with: " + newTrade.tradeCard + " to player: " + sendTo);
        System.out.println("PITplayer" + myPlayerNumber + " hand: " + toString(cards));
        System.out.println(toString(hand));
        String sendToJNDI = "jms/PITplayer" + sendTo;
        sendToQueue(sendToJNDI, newTrade);
    }
    
    // Reply rejecting an offer that was received.  Send back their card.
    private void doReplyReject(TenderOffer trade) throws Exception {
        if (halting) {
            return; // if halting, discard trade
        }
        
        System.out.println("PITplayer" + myPlayerNumber + " rejecting offer of: " + trade.tradeCard + " from player: " + trade.sourcePlayer);
        System.out.println("PITplayer" + myPlayerNumber + " hand: " + toString(cards));
        
        // if hit maxTrades limit, then stop sending trades
        if (maxTrades(maxTrades)) {
            return;
        }
        // If a monopoly is reached, then stop sending trades
        /*if (monopoly(hand)) {
            System.out.println("MONOPOLY REACHED! in Player" + myPlayerNumber);
            return;
        }*/
        
        // Send back their card that I am rejecting
        RejectOffer newTrade = new RejectOffer();
        newTrade.tradeCard = trade.tradeCard;
        newTrade.sourcePlayer = myPlayerNumber;
        
        
        //Send the card to the other player
        String sendToJNDI = "jms/PITplayer" + trade.sourcePlayer;
        sendToQueue(sendToJNDI, newTrade);
        
    }
    
    // Handle receiving a message that a previous offer has been accepted.
    // They would have replied with another card as payment.
    private void doReceiveAcceptOffer(AcceptOffer trade) throws Exception {
        if (halting) {
            return; // if halting, discard trade
        }
        // Having received a AcceptOffer from another Player, add it to my hand of cards
        cards.add(trade.tradeCard);
        updateHand(hand);
        // Keep track of incoming messages if certain conditions are met
        keepTrack(trade.sourcePlayer, trade.tradeCard);
        
        System.out.println("PITplayer" + myPlayerNumber + " received: " + trade.tradeCard + " as payment from player: " + trade.sourcePlayer);
        System.out.println("PITplayer" + myPlayerNumber + " hand: " + toString(cards));
        System.out.println(toString(hand));
        // Make another offer to a random player
        doTenderOffer();
    }
    
    // Handle receiving a reject message regarding a prior offer I made
    private void doReceiveRejectOffer(RejectOffer trade) throws Exception {
        if (halting) {
            return; // if halting, discard trade
        }
        // Because the offer was rejected, and returned, add it back into my cards
        cards.add(trade.tradeCard);
        updateHand(hand);
        // Keep track of incoming messages if certain conditions are met
        keepTrack(trade.sourcePlayer, trade.tradeCard);
        
        System.out.println("PITplayer" + myPlayerNumber + " received rejected offer of: " + trade.tradeCard + " from player: " + trade.sourcePlayer);
        System.out.println("PITplayer" + myPlayerNumber + " hand: " + toString(cards));
        System.out.println(toString(hand));
        // Make another offer to a random player
        doTenderOffer();
    }
    
    // Make an offer to a random player
    private void doTenderOffer() throws Exception {
        
        // if hit maxTrades limit, then stop sending trades
        if (maxTrades(maxTrades)) {
            return;
        }
        // If a monopoly is reached, then stop sending trades
        /*if (monopoly(hand)) {
            System.out.println("MONOPOLY REACHED! in Player" + myPlayerNumber);
            return;
        }*/
        
        /*
        * If numPlayers == 0, while we have received a TenderOffer, we have not
        * received our NewHand yet, so we don't know how many players there
        * are.  Therefore, don't send out a TenderOffer at this time.
        *
        */
        if (numPlayers == 0) {
            return;
        }
        
        // Create a new offer from my set of cards, and send to another player
        TenderOffer newTrade = new TenderOffer();
        
        // Initiate an offer by randomly choose a card to offer
        int position = (int) (Math.random() * cards.size());
        //System.out.println(position);
        newTrade.tradeCard = (String) cards.remove(position);
        newTrade.sourcePlayer = myPlayerNumber;
        updateHand(hand);
        System.out.println(toString(hand));
        
        // Find a random player to trade to (not including myself)
        int sendTo = myPlayerNumber;
        while (sendTo == myPlayerNumber) {
            sendTo = Math.round((float) Math.random() * (numPlayers - 1));
        }
        
        //Send the card to the other player
        System.out.println("PITplayer" + myPlayerNumber + " offered: " + newTrade.tradeCard + " to player: " + sendTo);
        String sendToJNDI = "jms/PITplayer" + sendTo;
        sendToQueue(sendToJNDI, newTrade);
        
    }
    
    // Handle situation when Marker is received
    private void doReceiveMarker(Marker marker) throws Exception {
        System.out.println("Player" + myPlayerNumber + " got Marker from Player" + marker.source);
        //System.out.println("halting? " + halting);
        
        boolean isStarter = false;
        if (marker.source == -1) { // if the marker is from the monitor
            // Record my state now
            recordState();
            // Do Marker sending rule
            sendMarker();
            isStarter = true;
            startRecord = true;
        }
        // If this is the first time this player has ever received a Marker
        if (participants.isEmpty()) {
            if (!isStarter) {
                recordState();
            }
            // update the flags of this player
            isStarter = true;
            startRecord = true;
            System.out.println("Player" + myPlayerNumber + " adding " + marker.source + " to my participants");
            participants.add(marker.source);           
            // Send out the Marker to each outgoing channel
            sendMarker();
        } else if (participants.size() < 4) { // If this player hasn't seen Markers from every other player
            System.out.println("Player" + myPlayerNumber + " adding " + marker.source + " to my participants");
            // Add the new source player to the participants list
            participants.add(marker.source);
            System.out.println("participants for player" + myPlayerNumber);
            for (int i = 0; i < participants.size(); i++) {
                System.out.print(participants.get(i) + " ");
            }
            System.out.println();
        } else { // This is last channel to receive marker on
            System.out.println("Player" + myPlayerNumber + ": I got all the markers back!");
            // Send records to the Monitor process
            String queueJNDI = "jms/PITsnapshot";
            sendToQueue(queueJNDI, state);
            // reset the state of this player
            participants.clear();
            state.clear();
            state.put("Player", myPlayerNumber);
            //isStarter = false;
            startRecord = false;
        }
    }
    
    // Record the current hand of this player
    private void recordState() {
        for (int i = 0; i < cards.size(); i++) {
            String comm = (String)(cards.get(i));
            if (!state.containsKey(comm)) {
                state.put(comm, 1);
            } else {
                int newCount = state.get(comm) + 1;
                state.put(comm, newCount);
            }
        }
    }
    
    // Keep track of incoming messages
    private void keepTrack(int source, String tradeCard) {
        // If the source player of this trade has not seen the Marker sent out by me,
        // keep track of the incoming messages
        if (!participants.contains(source) && startRecord) {
            if (state.containsKey(tradeCard)) {
                int newCount = state.get(tradeCard) + 1;
                state.put(tradeCard, newCount);
            } else {
                state.put(tradeCard, 1);
            }
        }
    }
    
    // Do Maker sending rule
    private void sendMarker() throws Exception {
        
        if (halting) {
            return;
        }
        
        Marker marker = new Marker(myPlayerNumber);
        // Send Marker to each outgoing channel
        for (int i = 0; i < 5; i++) {
            if (i != myPlayerNumber) {
                System.out.println("Player" + myPlayerNumber + " sending marker to " + i);
                String sendToJNDI = "jms/PITplayer" + i;
                sendToQueue(sendToJNDI, marker);
            }
        }
    }
    
    // Update the state of this player
    private void updateHand(HashMap<String, Integer> hand) {
        hand.clear();
        hand.put("Player", myPlayerNumber);
        for (int i = 0; i < cards.size(); i++) {
            String comm = (String)(cards.get(i));
            if (!hand.containsKey(comm)) {
                hand.put(comm, 1);
            } else {
                int newCount = hand.get(comm) + 1;
                hand.put(comm, newCount);
            }
        }
    }
    
    // Create a string of hand size and all cards
    private String toString(ArrayList hand) {
        
        String cardsString = "size: " + hand.size() + " ";
        for (int i = 0; i < hand.size(); i++) {
            cardsString += hand.get(i) + " ";
        }
        return cardsString;
    }
    
    // Create a printable version of the "state".
    private String toString(HashMap<String, Integer> state) {
        String stateString = "";
        for (Iterator it = state.entrySet().iterator(); it.hasNext();) {
            Map.Entry entry = (Map.Entry) it.next();
            String commodity = (String) entry.getKey();
            int number = ((Integer) entry.getValue()).intValue();
            stateString += "{" + commodity + ":" + number + "} ";
        }
        return stateString;
    }
    
    // Send an object to a Queue, given its JNDI name
    private void sendToQueue(String queueJNDI, Serializable message) throws Exception {
        // Gather necessary JMS resources
        Context ctxt = new InitialContext();
        Connection con = ((ConnectionFactory) ctxt.lookup("jms/myConnectionFactory")).createConnection();
        Session session = con.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue q = (Queue) ctxt.lookup(queueJNDI);
        MessageProducer writer = session.createProducer(q);
        ObjectMessage msg = session.createObjectMessage(message);
        // Send the object to the Queue
        writer.send(msg);
        session.close();
        con.close();
        ctxt.close();
    }
    
    // Stop trading when the max number of Trades is reached
    private boolean maxTrades(int max) {
        if ((numTrades % 100) == 0) {
            System.out.println("PITplayer" + myPlayerNumber + " numTrades: " + numTrades);
        }
        return (numTrades++ < max) ? false : true;
    }
    
    // Stop trading when a monopoly of one commodity is reached
    // A helper class for debug use
    private boolean monopoly(HashMap<String, Integer> hand) {
        for (Map.Entry<String, Integer> entry : hand.entrySet()) {
            if (!entry.getKey().equals("Player")) {
                if (entry.getValue() == 15) {
                    return true;
                }
            }
        }
        return false;
    }
}
