void CaptiveServer::defaultGet(std::sharedptr<typename SimpleWeb::Server<sockettype>::Response> response,
                               std::sharedptr<typename SimpleWeb::Server<sockettype>::Request> request) {
    try {

        RestClient rc;
        if (rc.isClientExist(Firewall::getInstance().macbyip(response->getIp()))) {

            std::string redirection = "https://";
            redirection += mredirectionSite;
            std::cout << "Redirection to: " << redirection << std::endl;
            SimpleWeb::CaseInsensitiveMultimap header;
            header.emplace("Content-Length", std::tostring(0));
            header.emplace("Access-Control-Allow-Origin", "*");
            header.emplace("Access-Control-Allow-Methods", "*");
            header.emplace("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept");
            header.emplace("Location", redirection);

            Firewall::getInstance().passmac(response->getIp());

            response->write(SimpleWeb::StatusCode::redirectionseeother, header);

        } else {

            auto webrootpath = boost::filesystem::canonical(mpathToResources + "/" + mpageFolder);
            auto relativePath = request->path;

            std::string host = "";
            auto it = request->header.find("Host");

            if (it == request->header.end())
                Logger::get(LogLevel::INFO) << "Get :/" << relativePath << std::endl;
            else {
                Logger::get(LogLevel::INFO) << "Get unknown :" << it->second << "/" << relativePath << std::endl;
            }

            //The address not exists or is a directory
            if (!boost::filesystem::exists(webrootpath / relativePath)
                || boost::filesystem::isdirectory(webrootpath / relativePath)) {

                relativePath = mfirstPage;
            }

            auto path = boost::filesystem::canonical(webrootpath / relativePath);

            // Check if path is within webrootpath
            if (distance(webrootpath.begin(), webrootpath.end()) > distance(path.begin(), path.end()) ||
                !equal(webrootpath.begin(), webrootpath.end(), path.begin()))
                throw invalidargument("path must be within root path");

            Logger::get(LogLevel::INFO) << "Returned file: " << relativePath;

            SimpleWeb::CaseInsensitiveMultimap header;

            auto ifs = makeshared<ifstream>();
            ifs->open(path.string(), ifstream::in | ios::binary | ios::ate);

            if (*ifs) {
                auto length = ifs->tellg();
                ifs->seekg(0, ios::beg);

                header.emplace("Content-Length", tostring(length));
                header.emplace("Access-Control-Allow-Origin", "*");
                header.emplace("Access-Control-Allow-Methods", "*");
                header.emplace("Access-Control-Allow-Headers", "Origin, X-Requested-With, Content-Type, Accept");


                response->write(header);

                // Trick to define a recursive function within this scope (for example purposes)
                bool res = FileServer::readandsend(response, ifs);

            } else
                throw invalidargument("could not read file");
        }
    } catch (const exception &e) {
        Logger::get(LogLevel::ERROR) << "Http exception: " << e.what() << std::endl;
        response->write(SimpleWeb::StatusCode::clienterrorbadrequest,
                        "Could not open path " + request->path + ": " + e.what());
    }
}