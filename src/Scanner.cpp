//
// Created by root on 25.03.18.
//

#include "Scanner.h"
#include "System.h"
#include "RestClient.h"
#include <pcap.h>
#include <iostream>

struct ieee80211_radiotap_hdr {
    uint8_t it_version;
    uint8_t it_pad;
    uint16_t it_len;
    uint32_t it_present;
} ieee80211_radiotap_hdr;

struct ieee80211_probe_req {
    uint16_t frame_control;
    uint16_t duration_id;
    uint8_t addr1[6];
    uint8_t addr2[6];
    uint8_t addr3[6];
    uint16_t seq_ctrl;
} ieee80211_probe_req;

tbb::concurrent_unordered_map <std::string, Visit> Scanner::visits;

std::mutex Scanner::visitsMutex;

Scanner::Scanner() {


}

Scanner::~Scanner() {
    for (auto a : visits) {
        std::cout << a.first << ": " << a.second.get_time_in() << std::endl;
    }
}

int Scanner::start() {
    char *dev, errbuf[PCAP_ERRBUF_SIZE];

    dev = pcap_lookupdev(errbuf);
    if (dev == NULL) {
        fprintf(stderr, "Couldn't find default device: %s\n", errbuf);
        return (2);
    }

    dev = "wlan1";
    printf("Device: %s\n", dev);

    /* open device for reading in no promiscuous mode */

    pcap_t *descr;
    descr = pcap_open_live(dev, BUFSIZ, 0, -1, errbuf);
    if (descr == NULL) {
        printf("pcap_open_live(): %s\n", errbuf);
        exit(1);
    }

    struct bpf_program fp;        /* hold compiled program */
    char *filter = "type mgt subtype probe-req"; //and ether host 30:39:26:e8:33:e3
    /* Now we'll compile the filter expression*/
    if (pcap_compile(descr, &fp, filter, 0, PCAP_NETMASK_UNKNOWN) == -1) {
        fprintf(stderr, "Error calling pcap_compile\n");
        exit(1);
    }

    /* set the filter */
    if (pcap_setfilter(descr, &fp) == -1) {
        fprintf(stderr, "Error setting filter\n");
        exit(1);
    }

    /* loop for callback function */
    pcap_loop(descr, -1, &Scanner::sniffCallback, NULL);

    return 0;
}

void Scanner::sniffCallback(u_char *arg, const struct pcap_pkthdr *pkthdr,
                            const u_char *packet) {

    struct ieee80211_radiotap_hdr *rhdr = (struct ieee80211_radiotap_hdr *) (packet);
    struct ieee80211_probe_req *prhdr = (struct ieee80211_probe_req *) (packet + rhdr->it_len);

    char mac[18];
    int n = sprintf(mac, "%.2x:%.2x:%.2x:%.2x:%.2x:%.2x ", prhdr->addr2[0], prhdr->addr2[1], prhdr->addr2[2],
                    prhdr->addr2[3], prhdr->addr2[4], prhdr->addr2[5]);

    std::string s_mac = std::string(mac);
    printf("Source m_mac: %s\n", mac);


//    Visit visit(std::string(mac), System::getTime());

    auto time = System::getTime();

   
    visitsMutex.lock();
    auto v = visits.find(s_mac);
    if (v == visits.end()) {
        visits.insert(std::pair<std::string, Visit>(s_mac, Visit(s_mac, time)));
    } else {
        v->second.setTime_out(time);
    }
    visitsMutex.unlock();
}
