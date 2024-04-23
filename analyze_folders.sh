#!/bin/bash

# Check if three arguments are passed
if [ "$#" -ne 3 ]; then
    echo "Usage: $0 folder1 folder2 filelist"
    exit 1
fi

# Assign the first two arguments to folder variables and the third to the file list
folder1="$1"
folder2="$2"
fileList="$3"

# Check if the file list exists
if [ ! -f "$fileList" ]; then
    echo "File list $fileList does not exist."
    exit 1
fi

mkdir -p results

# Read file names from the file list
while IFS= read -r file || [ -n "$file" ]; do
    file1="${folder1}/${file}"
    file2="${folder2}/${file}"

    # Check if the file exists in both folders
    if [ -f "$file1" ] && [ -f "$file2" ]; then
    	./invest2_analyze_cfg_diff_graph_search.py $file1 $file2 > results/$file.result
    else
        echo "File $file does not exist in both folders."
    fi
done < "$fileList"

