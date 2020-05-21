package com.jd.blockchain.ledger.core;

public interface LedgerEventQuery {
	
	EventGroup getSystemEvents();
	
	EventAccount getUserEvents(String address);
	
}
