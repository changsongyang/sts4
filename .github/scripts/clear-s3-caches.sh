set -e

s3_path=$1
#invalidation_json=`aws cloudfront create-invalidation --distribution-id ECAO9Q8651L8M --output json --paths "/${s3_path}/*"`
#echo "Invalidation response: ${invalidation_json}"
#invalidation_id=`echo $invalidation_json | jq -r '.Invalidation.Id'`
#invalidation_status=`echo $invalidation_json | jq -r '.Invalidation.Status'`
#echo "ID=${invalidation_id} Status=${invalidation_status}"
#while [ "${invalidation_status}" == "InProgress" ]
#do
#   echo "Invalidation status: ${invalidation_status}"
#   sleep 3
#   invalidation_status=`aws cloudfront get-invalidation --distribution-id ECAO9Q8651L8M --id $invalidation_id --output json | jq -r '.Invalidation.Status'`
#done
#echo "Final invalidation status: ${invalidation_status}"

s3_url=s3://dist.springsource.com/${s3_path}
files=`aws s3 cp ${s3_url} . --recursive --include "*" --dryrun`
counter=0
json=""
for file in $files
do
  if [ $counter == 0]; then
    json="\"{ \"files\": [\n"
  fi
  if [[ "$file" =~ ^"s3://dist.springsource.com" ]]; then
    let "counter++"
    path=${file:26}
    json="${json}\"http://dist.springsource.com${path}\",\n\"http://dist.springsource.com${path}\",\n\"http://download.springsource.com${path}\",\n\"https://download.springsource.com${path}\",\n"
  fi
  if [ $counter == 10]; then
    json="${json:-2}\n]}"
    echo $json
    json=""
  fi
done


