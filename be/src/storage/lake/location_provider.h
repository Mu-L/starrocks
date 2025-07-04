// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma once

#include <fmt/format.h>

#include <set>
#include <string>
#include <string_view>

#include "common/statusor.h"
#include "storage/lake/filenames.h"

namespace starrocks::lake {

static const char* const kMetadataDirectoryName = "meta";
static const char* const kTxnLogDirectoryName = "log";
static const char* const kSegmentDirectoryName = "data";

class LocationProvider {
public:
    virtual ~LocationProvider() = default;

    // The result should be guaranteed to not end with "/"
    virtual std::string root_location(int64_t tablet_id) const = 0;

    // In the share data mode, we use the virtual path of staros to access objects on remote storage.
    // When the same object is accessed by different Tablet, the path used may be different (this is
    // bad, we should use the real path), which will reduce the cache hit rate when using the virtual
    // path as the in-memory cache key. The purpose of this method is to obtain the real path as the
    // cache key and improve the cache hit rate.
    //
    // NOTE: This method returns a path instead of a URI, so it does not contain schemes like "s3://"
    //
    // NOTE: you should *NOT* use the real path to read and write objects, otherwise reading and writing
    // may fail or the behavior does not meet expectations (I know this sounds strange, but this is the
    // truth).
    virtual StatusOr<std::string> real_location(const std::string& virtual_path) const { return virtual_path; };

    std::string metadata_root_location(int64_t tablet_id) const {
        return join_path(root_location(tablet_id), kMetadataDirectoryName);
    }

    std::string txn_log_root_location(int64_t tablet_id) const {
        return join_path(root_location(tablet_id), kTxnLogDirectoryName);
    }

    std::string segment_root_location(int64_t tablet_id) const {
        return join_path(root_location(tablet_id), kSegmentDirectoryName);
    }

    std::string tablet_metadata_location(int64_t tablet_id, int64_t version) const {
        return join_path(metadata_root_location(tablet_id), tablet_metadata_filename(tablet_id, version));
    }

    std::string tablet_initial_metadata_location(int64_t tablet_id) const {
        return join_path(metadata_root_location(tablet_id), tablet_initial_metadata_filename());
    }

    std::string bundle_tablet_metadata_location(int64_t tablet_id, int64_t version) const {
        return join_path(metadata_root_location(tablet_id), tablet_metadata_filename(0, version));
    }

    std::string txn_log_location(int64_t tablet_id, int64_t txn_id) const {
        return join_path(txn_log_root_location(tablet_id), txn_log_filename(tablet_id, txn_id));
    }

    std::string txn_slog_location(int64_t tablet_id, int64_t txn_id) const {
        return join_path(txn_log_root_location(tablet_id), txn_slog_filename(tablet_id, txn_id));
    }

    std::string txn_vlog_location(int64_t tablet_id, int64_t version) const {
        return join_path(txn_log_root_location(tablet_id), txn_vlog_filename(tablet_id, version));
    }

    std::string combined_txn_log_location(int64_t tablet_id, int64_t txn_id) const {
        return join_path(txn_log_root_location(tablet_id), combined_txn_log_filename(txn_id));
    }

    std::string segment_location(int64_t tablet_id, std::string_view segment_name) const {
        return join_path(segment_root_location(tablet_id), segment_name);
    }

    std::string del_location(int64_t tablet_id, std::string_view del_name) const {
        return join_path(segment_root_location(tablet_id), del_name);
    }

    std::string delvec_location(int64_t tablet_id, std::string_view delvec_name) const {
        return join_path(segment_root_location(tablet_id), delvec_name);
    }

    std::string sst_location(int64_t tablet_id, std::string_view sst_name) const {
        return join_path(segment_root_location(tablet_id), sst_name);
    }

    std::string schema_file_location(int64_t tablet_id, int64_t schema_id) const {
        return join_path(root_location(tablet_id), schema_filename(schema_id));
    }

private:
    static std::string join_path(std::string_view parent, std::string_view child) {
        return fmt::format("{}/{}", parent, child);
    }
};

} // namespace starrocks::lake
